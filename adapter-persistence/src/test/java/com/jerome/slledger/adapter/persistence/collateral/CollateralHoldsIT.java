package com.jerome.slledger.adapter.persistence.collateral;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jerome.slledger.adapter.persistence.testing.Evidence;
import com.jerome.slledger.adapter.persistence.testing.LedgerDb;
import com.jerome.slledger.adapter.persistence.testing.LedgerFixtures;
import com.jerome.slledger.application.collateral.CollateralHolds;
import com.jerome.slledger.application.collateral.CollateralRefusedException;
import com.jerome.slledger.application.collateral.CollateralRefusedException.Reason;
import com.jerome.slledger.domain.money.Money;
import com.jerome.slledger.domain.party.CounterpartyId;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Try-Confirm-Cancel on collateral: the three ways a hold goes wrong when the participant does not
 * remember which transaction did what, and the same scripts against one that does. The naive
 * participant works on a table with no CHECK constraints, so the wrong number is readable.
 */
@Timeout(120)
class CollateralHoldsIT {

  private static final Currency HKD = Currency.getInstance("HKD");
  private static final CounterpartyId CP = new CounterpartyId("CP-001");
  private static final StringBuilder EVIDENCE = new StringBuilder();

  private static ConfigurableApplicationContext context;

  private JdbcClient jdbc;
  private TransactionTemplate transaction;
  private CollateralHolds holds;
  private NaiveCollateralHolds naive;
  private NaiveCollateralHolds guardedNaive;

  @BeforeAll
  static void start() {
    context = LedgerDb.start();
  }

  @AfterAll
  static void stop() {
    context.close();
    Evidence.write("10-tcc.txt", EVIDENCE.toString());
  }

  @BeforeEach
  void setUp() {
    jdbc = context.getBean(JdbcClient.class);
    transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
    holds = context.getBean(CollateralHolds.class);
    jdbc.sql("DELETE FROM tcc_transaction").update();
    jdbc.sql("DELETE FROM collateral_account").update();
    jdbc.sql("DELETE FROM counterparty").update();
    new LedgerFixtures(jdbc).counterparty("CP-001");
    jdbc.sql("INSERT INTO collateral_account VALUES ('CP-001', 'HKD', 1000.00, 0)").update();
    jdbc.sql("DROP TABLE IF EXISTS naive_account").update();
    jdbc.sql(
            "CREATE TABLE naive_account (id text PRIMARY KEY, available numeric(22,6) NOT NULL,"
                + " frozen numeric(22,6) NOT NULL)")
        .update();
    jdbc.sql("INSERT INTO naive_account VALUES ('A', 1000.00, 0)").update();
    naive = new NaiveCollateralHolds(jdbc, false);
    guardedNaive = new NaiveCollateralHolds(jdbc, true);
  }

  private static Money hkd(String amount) {
    return Money.of(amount, HKD);
  }

  private void inTransaction(Runnable work) {
    transaction.executeWithoutResult(status -> work.run());
  }

  private BigDecimal available() {
    return jdbc.sql("SELECT available FROM collateral_account WHERE counterparty_id = 'CP-001'")
        .query(BigDecimal.class)
        .single();
  }

  private BigDecimal frozen() {
    return jdbc.sql("SELECT frozen FROM collateral_account WHERE counterparty_id = 'CP-001'")
        .query(BigDecimal.class)
        .single();
  }

  private BigDecimal naiveAvailable() {
    return jdbc.sql("SELECT available FROM naive_account WHERE id = 'A'")
        .query(BigDecimal.class)
        .single();
  }

  private BigDecimal naiveFrozen() {
    return jdbc.sql("SELECT frozen FROM naive_account WHERE id = 'A'")
        .query(BigDecimal.class)
        .single();
  }

  private String state() {
    return "available=" + available().toPlainString() + " frozen=" + frozen().toPlainString();
  }

  private String naiveState() {
    return "available="
        + naiveAvailable().toPlainString()
        + " frozen="
        + naiveFrozen().toPlainString();
  }

  // ---- the three failures, against the participant that keeps no record --------------------

  @Test
  @DisplayName("a Cancel with no Try creates spendable collateral out of nothing")
  void reproducesEmptyRollback() {
    // See docs/decisions/10-messaging.md
    // The Try never arrived (or failed before doing anything). The coordinator cancels anyway.
    naive.cancel("A", new BigDecimal("100.00"));

    assertThat(naiveAvailable()).isEqualByComparingTo("1100.00");
    assertThat(naiveFrozen()).isEqualByComparingTo("-100.00");
    String broken = naiveState();

    inTransaction(() -> holds.cancel("X-1"));
    assertThat(available()).isEqualByComparingTo("1000.00");
    assertThat(frozen()).isEqualByComparingTo("0");
    EVIDENCE
        .append(
            "EMPTY ROLLBACK, participant with no record: account of 1000.00, Cancel(100.00) arrives with no Try -> ")
        .append(broken)
        .append(" (100.00 of spendable collateral that was never posted, and a negative hold)\n");
    EVIDENCE
        .append("  same script, participant with tcc_transaction: Cancel(X-1) with no Try -> ")
        .append(state())
        .append(", a tombstone row is written\n");
  }

  @Test
  @DisplayName(
      "the usual patch to Cancel stops the negative hold, and lets a late Try freeze funds for ever")
  void reproducesSuspension() {
    // See docs/decisions/10-messaging.md
    // Cancel first (nothing to release, so the guarded Cancel does nothing), then the delayed Try.
    guardedNaive.cancel("A", new BigDecimal("100.00"));
    guardedNaive.tryFreeze("A", new BigDecimal("100.00"));

    assertThat(naiveAvailable()).isEqualByComparingTo("900.00");
    assertThat(naiveFrozen()).isEqualByComparingTo("100.00");
    String stuck = naiveState();

    inTransaction(() -> holds.cancel("X-1"));
    assertThatThrownBy(() -> inTransaction(() -> holds.tryFreeze("X-1", CP, hkd("100.00"))))
        .isInstanceOfSatisfying(
            CollateralRefusedException.class,
            e -> assertThat(e.reason()).isEqualTo(Reason.ALREADY_CANCELLED));
    assertThat(available()).isEqualByComparingTo("1000.00");
    assertThat(frozen()).isEqualByComparingTo("0");
    EVIDENCE
        .append(
            "SUSPENSION, cancel patched with 'frozen >= amount': Cancel(100.00) then the late Try(100.00) -> ")
        .append(stuck)
        .append(
            " (100.00 held for a transaction whose Cancel has already come and gone; nothing will release it)\n");
    EVIDENCE
        .append(
            "  same script, participant with tcc_transaction: Cancel(X-1) then Try(X-1) -> Try refused (ALREADY_CANCELLED), ")
        .append(state())
        .append('\n');
  }

  @Test
  @DisplayName(
      "the patched Cancel of a transaction that never tried takes another transaction's hold")
  void reproducesGuardedCancelStealsAnotherHold() {
    // See docs/decisions/10-messaging.md
    guardedNaive.tryFreeze("A", new BigDecimal("100.00")); // transaction B's hold
    guardedNaive.cancel(
        "A", new BigDecimal("100.00")); // transaction A never tried; its Cancel arrives

    // B's 100.00 was released by someone else's Cancel; B's Confirm now consumes nothing that is
    // there.
    assertThat(naiveAvailable()).isEqualByComparingTo("1000.00");
    assertThat(naiveFrozen()).isEqualByComparingTo("0");
    guardedNaive.confirm("A", new BigDecimal("100.00"));
    assertThat(naiveFrozen()).isEqualByComparingTo("-100.00");
    EVIDENCE
        .append(
            "EMPTY ROLLBACK next to a live hold, patched Cancel: B holds 100.00, A (never tried) cancels 100.00 -> B's hold is gone; B's Confirm then leaves ")
        .append(naiveState())
        .append('\n');
  }

  @Test
  @DisplayName("a Confirm delivered twice consumes the next transaction's hold")
  void reproducesDoubleConfirm() {
    // See docs/decisions/10-messaging.md
    jdbc.sql("UPDATE naive_account SET available = 800.00, frozen = 200.00").update();
    // A and B hold 100.00 each. A's Confirm is delivered twice.
    naive.confirm("A", new BigDecimal("100.00"));
    naive.confirm("A", new BigDecimal("100.00"));

    assertThat(naiveFrozen()).isEqualByComparingTo("0");
    String broken = naiveState();

    jdbc.sql("UPDATE collateral_account SET available = 800.00, frozen = 200.00").update();
    jdbc.sql(
            "INSERT INTO tcc_transaction VALUES ('A', 'CP-001', 'HKD', 100.00, 'TRIED', now()),"
                + " ('B', 'CP-001', 'HKD', 100.00, 'TRIED', now())")
        .update();
    inTransaction(() -> holds.confirm("A"));
    inTransaction(() -> holds.confirm("A"));
    assertThat(frozen()).isEqualByComparingTo("100.00");
    EVIDENCE
        .append(
            "DOUBLE CONFIRM, participant with no record: A and B hold 100.00 each (frozen 200.00), Confirm(A) delivered twice -> ")
        .append(broken)
        .append(" (B's hold was consumed by A's duplicate)\n");
    EVIDENCE
        .append("  same script, participant with tcc_transaction: ")
        .append(state())
        .append(" (B's hold intact)\n");
  }

  // ---- the participant that keeps a record -------------------------------------------------

  @Test
  @DisplayName("try, confirm, and every repeat of them, leave the account exactly right")
  void confirmLifecycle() {
    inTransaction(() -> holds.tryFreeze("X-1", CP, hkd("250.00")));
    assertThat(available()).isEqualByComparingTo("750.00");
    assertThat(frozen()).isEqualByComparingTo("250.00");

    inTransaction(() -> holds.tryFreeze("X-1", CP, hkd("250.00"))); // a repeated Try
    assertThat(available()).isEqualByComparingTo("750.00");
    assertThat(frozen()).isEqualByComparingTo("250.00");

    inTransaction(() -> holds.confirm("X-1"));
    inTransaction(() -> holds.confirm("X-1"));
    assertThat(available()).isEqualByComparingTo("750.00");
    assertThat(frozen()).isEqualByComparingTo("0");
  }

  @Test
  @DisplayName("try, cancel, and every repeat of them, return the collateral once")
  void cancelLifecycle() {
    inTransaction(() -> holds.tryFreeze("X-1", CP, hkd("250.00")));
    inTransaction(() -> holds.cancel("X-1"));
    inTransaction(() -> holds.cancel("X-1"));

    assertThat(available()).isEqualByComparingTo("1000.00");
    assertThat(frozen()).isEqualByComparingTo("0");
  }

  @Test
  @DisplayName(
      "a Confirm after a Cancel, a Cancel after a Confirm and a Confirm with no Try are refused")
  void protocolViolationsAreRefused() {
    inTransaction(() -> holds.tryFreeze("C", CP, hkd("100.00")));
    inTransaction(() -> holds.cancel("C"));
    assertThatThrownBy(() -> inTransaction(() -> holds.confirm("C")))
        .isInstanceOfSatisfying(
            CollateralRefusedException.class,
            e -> assertThat(e.reason()).isEqualTo(Reason.ALREADY_CANCELLED));

    inTransaction(() -> holds.tryFreeze("D", CP, hkd("100.00")));
    inTransaction(() -> holds.confirm("D"));
    assertThatThrownBy(() -> inTransaction(() -> holds.cancel("D")))
        .isInstanceOfSatisfying(
            CollateralRefusedException.class,
            e -> assertThat(e.reason()).isEqualTo(Reason.ALREADY_CONFIRMED));

    assertThatThrownBy(() -> inTransaction(() -> holds.confirm("NEVER")))
        .isInstanceOfSatisfying(
            CollateralRefusedException.class,
            e -> assertThat(e.reason()).isEqualTo(Reason.NEVER_TRIED));

    assertThat(available()).isEqualByComparingTo("900.00");
    assertThat(frozen()).isZero();
  }

  @Test
  @DisplayName("a Try for more than is available is refused and leaves no record of the attempt")
  void insufficientIsRefusedAndLeavesNothing() {
    assertThatThrownBy(() -> inTransaction(() -> holds.tryFreeze("X-1", CP, hkd("1000.01"))))
        .isInstanceOfSatisfying(
            CollateralRefusedException.class,
            e -> assertThat(e.reason()).isEqualTo(Reason.INSUFFICIENT_COLLATERAL));
    assertThatThrownBy(
            () ->
                inTransaction(
                    () -> holds.tryFreeze("X-2", new CounterpartyId("CP-404"), hkd("1.00"))))
        .isInstanceOfSatisfying(
            CollateralRefusedException.class,
            e -> assertThat(e.reason()).isEqualTo(Reason.NO_ACCOUNT));

    assertThat(available()).isEqualByComparingTo("1000.00");
    assertThat(jdbc.sql("SELECT count(*) FROM tcc_transaction").query(Long.class).single())
        .isZero();
    // The same id can be tried again once there is enough, because the refused attempt left no row.
    inTransaction(() -> holds.tryFreeze("X-1", CP, hkd("1000.00")));
    assertThat(available()).isEqualByComparingTo("0");
  }

  @Test
  @DisplayName("a Try racing its own Cancel ends consistent whichever wins, every time")
  void trySharingItsCancelRace() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    int tryFirst = 0;
    int cancelFirst = 0;
    try {
      for (int round = 0; round < 40; round++) {
        String xid = "RACE-" + round;
        CyclicBarrier together = new CyclicBarrier(2);
        Future<Boolean> tried =
            pool.submit(
                () -> {
                  together.await();
                  try {
                    inTransaction(() -> holds.tryFreeze(xid, CP, hkd("100.00")));
                    return true;
                  } catch (CollateralRefusedException refused) {
                    return false;
                  }
                });
        Future<?> cancelled =
            pool.submit(
                () -> {
                  together.await();
                  inTransaction(() -> holds.cancel(xid));
                  return null;
                });
        boolean tryLanded = tried.get();
        cancelled.get();
        if (tryLanded) {
          tryFirst++;
        } else {
          cancelFirst++;
        }
        // Whichever won, the hold is not live: the collateral is all available and none frozen.
        assertThat(available()).isEqualByComparingTo("1000.00");
        assertThat(frozen()).isEqualByComparingTo("0");
      }
    } finally {
      pool.shutdownNow();
    }
    EVIDENCE
        .append("TRY vs CANCEL race, 40 rounds: Try landed before the Cancel in ")
        .append(tryFirst)
        .append(", the Cancel came first and the Try was refused in ")
        .append(cancelFirst)
        .append("; in all 40 the account ended available=1000.00 frozen=0\n");
  }
}
