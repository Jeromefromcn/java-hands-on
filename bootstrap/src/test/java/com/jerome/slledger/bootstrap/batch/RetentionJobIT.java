package com.jerome.slledger.bootstrap.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jerome.slledger.adapter.persistence.testing.Evidence;
import com.jerome.slledger.application.batch.Retention;
import com.jerome.slledger.application.collateral.CollateralHolds;
import com.jerome.slledger.application.collateral.CollateralRefusedException;
import com.jerome.slledger.batch.retention.RunRetentionJob;
import com.jerome.slledger.batch.retention.RunRetentionJob.Outcome;
import com.jerome.slledger.domain.money.Money;
import com.jerome.slledger.domain.party.CounterpartyId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Currency;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The retention job against rows of every age and state: what is old and finished goes, what is
 * recent, unfinished or unpublished stays, and a saga a dead process left unfinished is finished.
 * Defaults: outbox 7 days, TCC rows and sagas 30 days, consumed message ids 8 days.
 */
@Timeout(300)
class RetentionJobIT {

  private static final LocalDate DAY = LocalDate.of(2026, 9, 30);
  private static final Currency HKD = Currency.getInstance("HKD");
  private static final StringBuilder EVIDENCE = new StringBuilder();

  private static BatchLedger ledger;
  private static RunRetentionJob retention;
  private static TransactionTemplate transaction;

  @BeforeAll
  static void start() {
    ledger =
        BatchLedger.startWithOutbox(
            "--ledger.batch.chunk-size=5", "--ledger.retention.batch-size=2");
    retention = ledger.bean(RunRetentionJob.class);
    transaction = new TransactionTemplate(ledger.bean(PlatformTransactionManager.class));
  }

  @AfterAll
  static void stop() {
    Evidence.write("11-retention.txt", EVIDENCE.toString());
    ledger.close();
  }

  @BeforeEach
  void fresh() {
    ledger.resetLedger();
    ledger.resetBatchMetadata();
    for (String table :
        new String[] {
          "saga_log",
          "credit_reservation",
          "tcc_transaction",
          "collateral_account",
          "consumed_message"
        }) {
      ledger.jdbc.sql("DELETE FROM " + table).update();
    }
  }

  private void outbox(int count, String age, boolean published) {
    for (int i = 0; i < count; i++) {
      ledger
          .jdbc
          .sql(
              "INSERT INTO outbox_event (id, topic, message_key, type, payload, created_at,"
                  + " published_at) VALUES (gen_random_uuid(), 't', 'k', 'X', '{}',"
                  + " now() - ?::interval, "
                  + (published ? "now() - ?::interval" : "NULL")
                  + ")")
          .params(published ? new Object[] {age, age} : new Object[] {age})
          .update();
    }
  }

  private void tcc(String xid, String status, String age) {
    ledger
        .jdbc
        .sql(
            "INSERT INTO tcc_transaction (xid, status, updated_at) VALUES (?, ?,"
                + " now() - ?::interval)")
        .params(xid, status, age)
        .update();
  }

  private void saga(String id, boolean finished, String age, String reservationStatus) {
    ledger
        .jdbc
        .sql(
            "INSERT INTO saga_log (saga_id, seq, step, phase, outcome, at) VALUES"
                + " (?, 1, 'saga', 'FORWARD', 'STARTED', now() - ?::interval)")
        .params(id, age)
        .update();
    if (finished) {
      ledger
          .jdbc
          .sql(
              "INSERT INTO saga_log (saga_id, seq, step, phase, outcome, at) VALUES"
                  + " (?, 2, 'saga', 'FORWARD', 'DONE', now() - ?::interval)")
          .params(id, age)
          .update();
    }
    ledger
        .jdbc
        .sql(
            "INSERT INTO credit_reservation (saga_id, counterparty_id, amount, currency, status)"
                + " VALUES (?, 'CP-001', 100.00, 'HKD', ?)")
        .params(id, reservationStatus)
        .update();
  }

  private void consumed(int count, String age) {
    for (int i = 0; i < count; i++) {
      ledger
          .jdbc
          .sql(
              "INSERT INTO consumed_message (consumer, message_id, consumed_at) VALUES ('c',"
                  + " gen_random_uuid(), now() - ?::interval)")
          .param(age)
          .update();
    }
  }

  private long count(String table, String where) {
    return ledger
        .jdbc
        .sql("SELECT count(*) FROM " + table + " WHERE " + where)
        .query(Long.class)
        .single();
  }

  @Test
  @DisplayName("old finished rows go; recent, unpublished and unfinished ones stay")
  void removesWhatHasOutlivedItsRetentionAndOnlyThat() {
    outbox(3, "10 days", true);
    outbox(2, "1 day", true);
    outbox(2, "10 days", false);
    tcc("C-old", "CONFIRMED", "40 days");
    tcc("X-old", "CANCELLED", "40 days");
    tcc("X-new", "CANCELLED", "1 day");
    tcc("T-old", "TRIED", "40 days");
    saga("S-old", true, "40 days", "CONSUMED");
    saga("S-new", true, "1 day", "RELEASED");
    saga("S-stuck", false, "40 days", "RESERVED");
    consumed(2, "9 days");
    consumed(1, "1 day");

    Outcome outcome = retention.run(DAY);

    assertThat(outcome.status()).isEqualTo(RunRetentionJob.Status.COMPLETED);
    long execution = outcome.execution().getId();
    // Outbox: only the three published ones older than seven days.
    assertThat(count("outbox_event", "true")).isEqualTo(4);
    assertThat(count("outbox_event", "published_at IS NULL")).isEqualTo(2);
    assertThat(ledger.stepContextLong(execution, "purgeOutbox", "purged")).isEqualTo(3);
    // TCC: the two terminal rows past thirty days; the recent tombstone and the in-flight one stay.
    // (Recovery of S-stuck adds a tombstone of its own, which is new.)
    assertThat(count("tcc_transaction", "xid IN ('C-old', 'X-old')")).isZero();
    assertThat(count("tcc_transaction", "xid IN ('X-new', 'T-old')")).isEqualTo(2);
    assertThat(ledger.stepContextLong(execution, "purgeTombstones", "purged")).isEqualTo(2);
    // Sagas: S-old (finished long ago) and its reservation; S-new stays; S-stuck was recovered and
    // its fresh terminal entry keeps it.
    assertThat(count("saga_log", "saga_id = 'S-old'")).isZero();
    assertThat(count("credit_reservation", "saga_id = 'S-old'")).isZero();
    assertThat(count("saga_log", "saga_id = 'S-new'")).isEqualTo(2);
    assertThat(ledger.stepContextLong(execution, "purgeSagas", "purged")).isEqualTo(1);
    assertThat(ledger.stepContextLong(execution, "recoverSagas", "recovered")).isEqualTo(1);
    assertThat(count("saga_log", "saga_id = 'S-stuck' AND step = 'saga' AND outcome = 'DONE'"))
        .isEqualTo(1);
    assertThat(
            ledger
                .jdbc
                .sql("SELECT status FROM credit_reservation WHERE saga_id = 'S-stuck'")
                .query(String.class)
                .single())
        .isEqualTo("RELEASED");
    // Consumed ids: the two older than eight days.
    assertThat(count("consumed_message", "true")).isEqualTo(1);
    assertThat(ledger.stepContextLong(execution, "purgeConsumedIds", "purged")).isEqualTo(2);
    EVIDENCE.append(
        """
        Stage 11, section 13: the retention job on rows of every age. Defaults: outbox 7 days, TCC
        rows and sagas 30 days, consumed ids 8 days; purge batches of 2 rows (the test sets it to
        see the loop). Real Postgres.

        before: outbox 3 published 10 d old + 2 published 1 d old + 2 unpublished 10 d old;
                tcc: CONFIRMED 40 d, CANCELLED 40 d, CANCELLED 1 d, TRIED 40 d;
                sagas: finished 40 d ago, finished 1 d ago, unfinished since 40 d ago;
                consumed ids: 2 of 9 d, 1 of 1 d.
        steps:
        %s
        counts saved by the steps: recovered %d sagas; purged %d saga(s), %d tcc row(s), %d outbox
        row(s), %d consumed id(s)
        after : outbox %d rows (2 unpublished, 2 recent published); tcc rows that remain: X-new
                (recent tombstone), T-old (in flight) and the tombstone the recovery wrote;
                saga S-old gone with its reservation, S-new kept, S-stuck recovered (reservation now
                RELEASED) and kept by its new terminal entry; consumed ids %d.
        """
            .formatted(
                ledger.stepTable(execution),
                ledger.stepContextLong(execution, "recoverSagas", "recovered"),
                ledger.stepContextLong(execution, "purgeSagas", "purged"),
                ledger.stepContextLong(execution, "purgeTombstones", "purged"),
                ledger.stepContextLong(execution, "purgeOutbox", "purged"),
                ledger.stepContextLong(execution, "purgeConsumedIds", "purged"),
                count("outbox_event", "true"),
                count("consumed_message", "true")));
  }

  @Test
  @DisplayName("a second run of the same date is answered already done, and a different date runs")
  void oncePerDate() {
    assertThat(retention.run(DAY).status()).isEqualTo(RunRetentionJob.Status.COMPLETED);
    assertThat(retention.run(DAY).status()).isEqualTo(RunRetentionJob.Status.ALREADY_DONE);
    assertThat(retention.run(DAY.plusDays(1)).status()).isEqualTo(RunRetentionJob.Status.COMPLETED);
  }

  @Test
  @DisplayName(
      "reproducesTombstonePurgedTooEarly: a cancelled hold's tombstone is what refuses a late Try")
  void reproducesTombstonePurgedTooEarly() {
    // See docs/decisions/11-batch.md
    ledger
        .jdbc
        .sql("INSERT INTO collateral_account VALUES ('CP-001', 'HKD', 1000000.00, 0)")
        .update();
    CollateralHolds holds = ledger.bean(CollateralHolds.class);
    Retention purges = ledger.bean(Retention.class);
    Money amount = Money.of("100.00", HKD);
    CounterpartyId cp = new CounterpartyId("CP-001");

    // X-early is cancelled before its Try, and a purge with no retention at all then removes its
    // tombstone. X-kept is cancelled after that purge, so its tombstone is intact.
    transaction.executeWithoutResult(s -> holds.cancel("X-early"));
    transaction.executeWithoutResult(
        s -> purges.purgeTombstones(Instant.now().plusSeconds(60), 100));
    transaction.executeWithoutResult(s -> holds.cancel("X-kept"));

    // The late Try for X-early finds no tombstone and freezes funds that nothing will ever release.
    transaction.executeWithoutResult(s -> holds.tryFreeze("X-early", cp, amount));
    assertThat(frozen()).isEqualByComparingTo("100.00");

    // The late Try for X-kept finds its tombstone and is refused.
    assertThatThrownBy(
            () -> transaction.executeWithoutResult(s -> holds.tryFreeze("X-kept", cp, amount)))
        .isInstanceOf(CollateralRefusedException.class);
    assertThat(frozen()).isEqualByComparingTo("100.00");
    Evidence.write(
        "11-retention-tombstone.txt",
        """
        Stage 11, section 13: why a TCC tombstone has a retention longer than a late message can be.
        X-early is cancelled before its Try and a purge with no retention removes its tombstone;
        X-kept is cancelled after that purge, so its tombstone is intact. Then each one's late Try
        arrives.

        X-early (tombstone purged)  : Try accepted; frozen collateral of CP-001 = 100.00 HKD, held for
                                      an attempt that was cancelled, which nothing will release
        X-kept  (tombstone intact)  : Try refused (CollateralRefusedException); frozen stays 100.00
        """);
  }

  private java.math.BigDecimal frozen() {
    return ledger
        .jdbc
        .sql("SELECT frozen FROM collateral_account WHERE counterparty_id = 'CP-001'")
        .query(java.math.BigDecimal.class)
        .single();
  }
}
