package com.jerome.slledger.adapter.persistence.accrual;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jerome.slledger.adapter.persistence.testing.LedgerDb;
import com.jerome.slledger.adapter.persistence.testing.LedgerFixtures;
import com.jerome.slledger.application.accrual.AccrualCandidate;
import com.jerome.slledger.application.accrual.AccrualCandidates;
import com.jerome.slledger.application.accrual.AccrualLedger;
import com.jerome.slledger.application.accrual.BookResult;
import com.jerome.slledger.application.accrual.PartitionScheme;
import com.jerome.slledger.application.accrual.Slice;
import com.jerome.slledger.domain.accrual.AccrualEntry;
import com.jerome.slledger.domain.loan.LoanPositionId;
import com.jerome.slledger.domain.money.Money;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The two ports the accrual batch reads and writes through, against a real Postgres. */
class AccrualAdaptersIT {

  private static final LocalDate DAY = LocalDate.of(2026, 9, 30);

  private static ConfigurableApplicationContext context;

  private JdbcClient jdbc;
  private LedgerFixtures fixtures;
  private AccrualCandidates candidates;
  private AccrualLedger ledger;
  private TransactionTemplate transaction;

  @BeforeAll
  static void start() {
    context = LedgerDb.start();
  }

  @AfterAll
  static void stop() {
    context.close();
  }

  @BeforeEach
  void setUp() {
    jdbc = context.getBean(JdbcClient.class);
    fixtures = new LedgerFixtures(jdbc);
    candidates = context.getBean(AccrualCandidates.class);
    ledger = context.getBean(AccrualLedger.class);
    transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    jdbc.sql("DELETE FROM accrual_entry").update();
    jdbc.sql("DELETE FROM loan_trade").update();
    jdbc.sql("DELETE FROM loan_position").update();
    jdbc.sql("DELETE FROM security").update();
    jdbc.sql("DELETE FROM counterparty").update();
    fixtures.counterparty("CP-001").counterparty("CP-002").security("HK0000000001", "41.26");
  }

  private List<String> ids(List<AccrualCandidate> page) {
    return page.stream().map(AccrualCandidate::positionId).toList();
  }

  private List<AccrualCandidate> everything(Slice slice, int pageSize) {
    List<AccrualCandidate> all = new ArrayList<>();
    Optional<String> after = Optional.empty();
    while (true) {
      List<AccrualCandidate> page = candidates.page(DAY, slice, after, pageSize);
      all.addAll(page);
      if (page.size() < pageSize) {
        return all;
      }
      after = Optional.of(page.getLast().positionId());
    }
  }

  @Test
  @DisplayName("keyset pages are in id order, disjoint and together complete")
  void pagesAreDisjointAndComplete() {
    fixtures.positions("A", 25, "CP-001", "HK0000000001").borrowTradesForAll("A", DAY);

    List<AccrualCandidate> all = everything(Slice.ALL, 7);

    assertThat(all)
        .hasSize(25)
        .allSatisfy(c -> assertThat(c).isInstanceOf(AccrualCandidate.Valid.class));
    assertThat(ids(all)).doesNotHaveDuplicates().isSorted();
    assertThat(ids(candidates.page(DAY, Slice.ALL, Optional.of("A0010"), 3)))
        .containsExactly("A0011", "A0012", "A0013");
  }

  @Test
  @DisplayName("a valid candidate carries the position and the security's price")
  void validCandidateCarriesThePrice() {
    fixtures.position("A0001", "CP-001", "HK0000000001", 500).borrowTradesForAll("A", DAY);

    AccrualCandidate.Valid valid =
        (AccrualCandidate.Valid) candidates.page(DAY, Slice.ALL, Optional.empty(), 10).getFirst();

    assertThat(valid.position().outstandingQuantity()).isEqualTo(500);
    assertThat(valid.unitPrice()).isEqualTo(Money.of("41.26", Currency.getInstance("HKD")));
  }

  @Test
  @DisplayName("a row that cannot be restored comes back as Corrupt and does not fail the page")
  void corruptRowDoesNotFailThePage() {
    fixtures.positions("A", 3, "CP-001", "HK0000000001");
    jdbc.sql("UPDATE loan_position SET rule_type = 'FLOATING' WHERE id = 'A0002'").update();
    jdbc.sql("UPDATE loan_position SET status = 'NONSENSE' WHERE id = 'A0003'").update();
    fixtures.borrowTradesForAll("A", DAY);

    List<AccrualCandidate> page = candidates.page(DAY, Slice.ALL, Optional.empty(), 10);

    assertThat(page).hasSize(3);
    assertThat(page.get(0)).isInstanceOf(AccrualCandidate.Valid.class);
    assertThat(page.get(1)).isInstanceOf(AccrualCandidate.Corrupt.class);
    assertThat(((AccrualCandidate.Corrupt) page.get(1)).reason()).contains("FLOATING");
    assertThat(page.get(2)).isInstanceOf(AccrualCandidate.Corrupt.class);
  }

  @Test
  @DisplayName("closed positions and positions that settle after the date are not active")
  void onlyActivePositionsOnTheDate() {
    fixtures.positions("A", 4, "CP-001", "HK0000000001").borrowTradesForAll("A", DAY);
    jdbc.sql(
            "UPDATE loan_position SET status = 'CLOSED', outstanding_quantity = 0"
                + " WHERE id = 'A0001'")
        .update();
    jdbc.sql(
            "UPDATE loan_trade SET settlement_date = ?, trade_date = ? WHERE position_id = 'A0002'")
        .params(DAY.plusDays(1), DAY)
        .update();

    assertThat(ids(everything(Slice.ALL, 10))).containsExactly("A0003", "A0004");
    assertThat(ids(candidates.page(DAY.plusDays(1), Slice.ALL, Optional.empty(), 10)))
        .containsExactly("A0002", "A0003", "A0004");
  }

  @Test
  @DisplayName("every scheme's slices are disjoint and together cover every active position")
  void slicesCoverTheKeyspace() {
    fixtures.positions("A", 30, "CP-001", "HK0000000001");
    fixtures.positions("B", 5, "CP-002", "HK0000000001");
    fixtures.borrowTradesForAll("A", DAY).borrowTradesForAll("B", DAY);
    List<String> expected = ids(everything(Slice.ALL, 100));
    assertThat(expected).hasSize(35);

    for (PartitionScheme scheme : PartitionScheme.values()) {
      List<Slice> slices = candidates.slices(scheme, DAY, 4);
      Set<String> seen = new HashSet<>();
      for (Slice slice : slices) {
        for (String id : ids(everything(slice, 6))) {
          assertThat(seen.add(id)).as("%s: %s read twice", scheme, id).isTrue();
        }
      }
      assertThat(seen).as(scheme.name()).containsExactlyInAnyOrderElementsOf(expected);
    }
    assertThat(candidates.slices(PartitionScheme.NONE, DAY, 4)).hasSize(1);
    assertThat(candidates.slices(PartitionScheme.COUNTERPARTY, DAY, 4)).hasSize(2);
    assertThat(candidates.slices(PartitionScheme.BALANCED, DAY, 4)).hasSize(4);
  }

  private AccrualEntry entry(String position, LocalDate day, String fee, String currency) {
    return new AccrualEntry(
        new LoanPositionId(position), day, Money.of(fee, Currency.getInstance(currency)));
  }

  @Test
  @DisplayName("booking the same entries again inserts none and reports them as present")
  void bookingIsIdempotent() {
    fixtures.positions("A", 3, "CP-001", "HK0000000001");
    List<AccrualEntry> entries =
        List.of(
            entry("A0001", DAY, "4.13", "HKD"),
            entry("A0002", DAY, "4.13", "HKD"),
            entry("A0003", DAY, "4.13", "HKD"));

    BookResult first = transaction.execute(s -> ledger.book(entries));
    BookResult second = transaction.execute(s -> ledger.book(entries));
    BookResult overlap =
        transaction.execute(
            s ->
                ledger.book(
                    List.of(entries.get(2), entry("A0003", DAY.plusDays(1), "4.13", "HKD"))));

    assertThat(first).isEqualTo(new BookResult(3, 0));
    assertThat(second).isEqualTo(new BookResult(0, 3));
    assertThat(overlap).isEqualTo(new BookResult(1, 1));
    assertThat(jdbc.sql("SELECT count(*) FROM accrual_entry").query(Long.class).single())
        .isEqualTo(4L);
  }

  @Test
  @DisplayName("an existing entry is never overwritten by a later booking")
  void existingEntryIsNotOverwritten() {
    fixtures.positions("A", 1, "CP-001", "HK0000000001");
    transaction.execute(s -> ledger.book(List.of(entry("A0001", DAY, "4.13", "HKD"))));

    transaction.execute(s -> ledger.book(List.of(entry("A0001", DAY, "9.99", "HKD"))));

    assertThat(
            jdbc.sql("SELECT fee_amount FROM accrual_entry WHERE position_id = 'A0001'")
                .query(java.math.BigDecimal.class)
                .single())
        .isEqualByComparingTo("4.13");
  }

  @Test
  @DisplayName("more rows than one statement carries are booked in full")
  void largeChunkIsBookedInFull() {
    fixtures.positions("A", 2_500, "CP-001", "HK0000000001");
    List<AccrualEntry> entries = new ArrayList<>();
    for (int i = 1; i <= 2_500; i++) {
      entries.add(entry("A%04d".formatted(i), DAY, "4.13", "HKD"));
    }

    BookResult result = transaction.execute(s -> ledger.book(entries));

    assertThat(result).isEqualTo(new BookResult(2_500, 0));
  }

  @Test
  @DisplayName("booking outside a transaction is refused")
  void bookingNeedsATransaction() {
    assertThatThrownBy(() -> ledger.book(List.of(entry("A0001", DAY, "4.13", "HKD"))))
        .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
  }

  @Test
  @DisplayName("a fee keeps its digits and its currency in HKD, JPY and BHD")
  void moneyRoundTrips() {
    fixtures.positions("A", 3, "CP-001", "HK0000000001");
    transaction.execute(
        s ->
            ledger.book(
                List.of(
                    entry("A0001", DAY, "4.13", "HKD"),
                    entry("A0002", DAY, "150", "JPY"),
                    entry("A0003", DAY, "1.235", "BHD"))));

    List<String> stored =
        jdbc.sql(
                "SELECT fee_amount::text || ' ' || fee_currency FROM accrual_entry"
                    + " ORDER BY position_id")
            .query(String.class)
            .list();

    assertThat(stored).containsExactly("4.130000 HKD", "150.000000 JPY", "1.235000 BHD");
  }
}
