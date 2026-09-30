package com.jerome.slledger.adapter.persistence.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jerome.slledger.adapter.persistence.accrual.JdbcAccrualEntryQuery;
import com.jerome.slledger.adapter.persistence.testing.LedgerDb;
import com.jerome.slledger.adapter.persistence.testing.LedgerFixtures;
import com.jerome.slledger.application.port.AccrualEntryQuery;
import com.jerome.slledger.application.port.PositionDetailQuery;
import com.jerome.slledger.application.port.PositionRow;
import com.jerome.slledger.domain.accrual.AccrualEntry;
import com.jerome.slledger.domain.loan.LoanPositionId;
import com.jerome.slledger.domain.loan.LoanStatus;
import com.jerome.slledger.domain.money.Money;
import java.time.LocalDate;
import java.util.Currency;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

/** The two stage 7 read ports: one position by id, and its entries over a date range. */
class ReadQueriesIT {

  private static final LocalDate DAY_1 = LocalDate.of(2026, 3, 2);

  private static ConfigurableApplicationContext context;
  private static PositionDetailQuery detail;
  private static AccrualEntryQuery entries;

  @BeforeAll
  static void start() {
    context = LedgerDb.start();
    JdbcClient jdbc = context.getBean(JdbcClient.class);
    new LedgerFixtures(jdbc)
        .counterparty("CP-001")
        .security("HK0000000001", "41.26")
        .position("LP-A", "CP-001", "HK0000000001", 100)
        .position("LP-B", "CP-001", "HK0000000001", 100)
        .entries("LP-A", DAY_1, 10, "1.25")
        .entries("LP-B", DAY_1, 10, "9.99");
    jdbc.sql(
            "INSERT INTO loan_position (id, counterparty_id, isin, original_quantity,"
                + " outstanding_quantity, status, collateral_amount, collateral_currency,"
                + " rule_type, flat_rate) VALUES"
                + " ('LP-JPY', 'CP-001', 'HK0000000001', 100, 40, 'PARTIALLY_RETURNED',"
                + " 150000, 'JPY', 'FLAT', 0.0365),"
                + " ('LP-BAD', 'CP-001', 'HK0000000001', 100, 100, 'OPEN',"
                + " 1000.005, 'HKD', 'FLAT', 0.0365)")
        .update();
    detail = context.getBean(PositionDetailQuery.class);
    entries = context.getBean(AccrualEntryQuery.class);
    assertThat(detail).isInstanceOf(JdbcPositionDetailQuery.class);
    assertThat(entries).isInstanceOf(JdbcAccrualEntryQuery.class);
  }

  @AfterAll
  static void stop() {
    context.close();
  }

  @Test
  @DisplayName("a position is found by id with its money intact")
  void findsPosition() {
    PositionRow row = detail.find(new LoanPositionId("LP-JPY")).orElseThrow();

    assertThat(row.status()).isEqualTo(LoanStatus.PARTIALLY_RETURNED);
    assertThat(row.outstandingQuantity()).isEqualTo(40);
    assertThat(row.collateral()).isEqualTo(Money.of("150000", Currency.getInstance("JPY")));
    assertThat(row.collateral().amount().scale()).isZero();
  }

  @Test
  @DisplayName("an unknown id is empty, not an error")
  void unknownIsEmpty() {
    assertThat(detail.find(new LoanPositionId("LP-NONE"))).isEmpty();
  }

  @Test
  @DisplayName("a row with more precision than its currency is refused, not rounded")
  void overPreciseRefused() {
    assertThatThrownBy(() -> detail.find(new LoanPositionId("LP-BAD")))
        .isInstanceOf(ArithmeticException.class);
  }

  @Test
  @DisplayName("entries are the position's own, both ends inclusive, in date order")
  void entriesInRange() {
    var found = entries.of(new LoanPositionId("LP-A"), DAY_1.plusDays(2), DAY_1.plusDays(5));

    assertThat(found)
        .extracting(AccrualEntry::accruedOn)
        .containsExactly(
            DAY_1.plusDays(2), DAY_1.plusDays(3), DAY_1.plusDays(4), DAY_1.plusDays(5));
    assertThat(found)
        .extracting(AccrualEntry::fee)
        .containsOnly(Money.of("1.25", Currency.getInstance("HKD")));
  }

  @Test
  @DisplayName("a range with no entries is an empty list")
  void emptyRange() {
    assertThat(entries.of(new LoanPositionId("LP-A"), DAY_1.minusDays(9), DAY_1.minusDays(1)))
        .isEmpty();
  }
}
