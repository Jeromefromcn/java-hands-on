package com.jerome.slledger.bootstrap.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.persistence.testing.Evidence;
import com.jerome.slledger.application.cache.StaleFencingTokenException;
import com.jerome.slledger.batch.accrual.RunAccrual;
import com.jerome.slledger.batch.accrual.RunAccrual.Outcome;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.dao.TransientDataAccessResourceException;

/**
 * One bad row among many, a transient failure, and a failure that must not be retried. 200
 * positions, chunks of 20, a skip limit of 10, three retries per chunk.
 */
@Timeout(300)
class SkipRetryIT {

  private static final StringBuilder EVIDENCE =
      new StringBuilder(
          """
          Stage 11, section 6: skip and retry on the accrual step. 200 active positions, chunk
          size 20 (10 chunks), skip limit 10, retry limit 3 (three retries after the first attempt). Real Postgres. Step rows are read from
          BATCH_STEP_EXECUTION.

          """);

  private static BatchLedger ledger;
  private static RunAccrual accrual;

  @BeforeAll
  static void start() {
    ledger =
        BatchLedger.start(
            List.of(InjectFailure.class),
            "--ledger.batch.chunk-size=20",
            "--ledger.batch.skip-limit=10",
            "--ledger.batch.retry-limit=3");
    accrual = ledger.bean(RunAccrual.class);
  }

  @AfterAll
  static void stop() {
    Evidence.write("11-skip-retry.txt", EVIDENCE.toString());
    ledger.close();
  }

  @BeforeEach
  void fresh() {
    InjectFailure.reset();
    ledger.resetLedger();
    ledger.resetBatchMetadata();
    ledger.seed(200);
  }

  private void corrupt(String... ids) {
    for (String id : ids) {
      ledger
          .jdbc
          .sql("UPDATE loan_position SET rule_type = 'FLOATING' WHERE id = ?")
          .param(id)
          .update();
    }
  }

  private long stepValue(long execution, String column) {
    return ledger
        .jdbc
        .sql("SELECT " + column + " FROM batch_step_execution WHERE job_execution_id = ?")
        .param(execution)
        .query(Long.class)
        .single();
  }

  @Test
  @DisplayName("corrupt rows are skipped and recorded, and read = written + skipped")
  void corruptRowsAreSkippedAndRecorded() {
    corrupt("P000010", "P000050", "P000090", "P000130", "P000170");

    Outcome outcome = accrual.run(BatchLedger.DAY);

    long execution = outcome.execution().getId();
    assertThat(outcome.status()).isEqualTo(RunAccrual.Status.COMPLETED);
    assertThat(ledger.count("accrual_entry")).isEqualTo(195);
    assertThat(stepValue(execution, "process_skip_count")).isEqualTo(5);
    assertThat(stepValue(execution, "read_count")).isEqualTo(200);
    assertThat(stepValue(execution, "write_count")).isEqualTo(195);
    assertThat(stepValue(execution, "commit_count")).isEqualTo(10);
    assertThat(
            ledger
                .jdbc
                .sql("SELECT item_key FROM batch_skip WHERE job = 'dailyAccrual' ORDER BY item_key")
                .query(String.class)
                .list())
        .containsExactly("P000010", "P000050", "P000090", "P000130", "P000170");
    assertThat(
            ledger
                .jdbc
                .sql("SELECT reason FROM batch_skip WHERE item_key = 'P000010'")
                .query(String.class)
                .single())
        .contains("CorruptPositionException")
        .contains("FLOATING");
    assertThat(
            ledger
                .jdbc
                .sql("SELECT count(*) FROM accrual_entry WHERE position_id = 'P000010'")
                .query(Long.class)
                .single())
        .isZero();
    EVIDENCE.append(
        """
        -- five corrupt rows (rule_type FLOATING, which the ledger cannot store or restore)
        %s
        accrual_entry rows: %d; batch_skip rows: %d
        read (%d) = written (%d) + skipped (%d)

        """
            .formatted(
                ledger.stepTable(execution),
                ledger.count("accrual_entry"),
                ledger.count("batch_skip"),
                stepValue(execution, "read_count"),
                stepValue(execution, "write_count"),
                stepValue(execution, "process_skip_count")));
  }

  @Test
  @DisplayName("the eleventh skip fails the step; what was committed stays, and the run can resume")
  void skipLimitFailsTheStep() {
    corrupt(
        "P000010", "P000020", "P000030", "P000040", "P000050", "P000060", "P000070", "P000080",
        "P000090", "P000100", "P000110");

    Outcome outcome = accrual.run(BatchLedger.DAY);

    long execution = outcome.execution().getId();
    assertThat(outcome.status()).isEqualTo(RunAccrual.Status.FAILED);
    assertThat(stepValue(execution, "process_skip_count")).isGreaterThanOrEqualTo(10);
    assertThat(ledger.count("accrual_entry")).isLessThan(189);
    assertThat(
            ledger
                .jdbc
                .sql("SELECT status FROM batch_step_execution WHERE job_execution_id = ?")
                .param(execution)
                .query(String.class)
                .single())
        .isEqualTo("FAILED");
    EVIDENCE.append(
        """
        -- eleven corrupt rows against a limit of 10
        %s
        accrual_entry rows: %d; batch_skip rows: %d; step exit message: %s

        """
            .formatted(
                ledger.stepTable(execution),
                ledger.count("accrual_entry"),
                ledger.count("batch_skip"),
                ledger
                    .jdbc
                    .sql(
                        "SELECT left(exit_message, 90) FROM batch_step_execution"
                            + " WHERE job_execution_id = ?")
                    .param(execution)
                    .query(String.class)
                    .single()));
  }

  @Test
  @DisplayName("a transient failure of one chunk is retried in place and the run completes")
  void transientFailureIsRetried() {
    // Calls 3 and 4 are the first two attempts at chunk 3; the third attempt succeeds.
    InjectFailure.on(
        call -> Set.of(3, 4).contains(call),
        () -> new TransientDataAccessResourceException("injected: connection reset"));

    Outcome outcome = accrual.run(BatchLedger.DAY);

    long execution = outcome.execution().getId();
    assertThat(outcome.status()).isEqualTo(RunAccrual.Status.COMPLETED);
    assertThat(ledger.count("accrual_entry")).isEqualTo(200);
    assertThat(InjectFailure.CALLS.get()).isEqualTo(12); // 10 chunks + 2 extra attempts
    EVIDENCE.append(
        """
        -- a transient failure on the first two attempts at chunk 3
        %s
        writer attempts: %d (10 chunks + 2 retries); accrual_entry rows: %d

        """
            .formatted(
                ledger.stepTable(execution),
                InjectFailure.CALLS.get(),
                ledger.count("accrual_entry")));
  }

  @Test
  @DisplayName("a transient failure that outlasts the retry limit fails the step")
  void transientFailureBeyondTheLimitFailsTheStep() {
    InjectFailure.on(
        call -> call >= 3, () -> new TransientDataAccessResourceException("injected: still down"));

    Outcome outcome = accrual.run(BatchLedger.DAY);

    assertThat(outcome.status()).isEqualTo(RunAccrual.Status.FAILED);
    assertThat(ledger.count("accrual_entry")).isEqualTo(40);
    // Chunks 1 and 2, then the first attempt at chunk 3 and three retries: a retry limit of 3 is
    // three retries, so four attempts.
    assertThat(InjectFailure.CALLS.get()).isEqualTo(6);
    EVIDENCE.append(
        """
        -- a transient failure that never clears: %d writer attempts (chunks 1 and 2, then four
        attempts at chunk 3: the first and three retries), then the step failed with %d entries
        booked (chunks 1 and 2)

        """
            .formatted(InjectFailure.CALLS.get(), ledger.count("accrual_entry")));
  }

  @Test
  @DisplayName("a stale fencing token is neither retried nor skipped: one attempt, the step fails")
  void staleTokenFailsTheStepAtOnce() {
    InjectFailure.on(call -> call == 3, () -> new StaleFencingTokenException("accrual", 1));

    Outcome outcome = accrual.run(BatchLedger.DAY);

    assertThat(outcome.status()).isEqualTo(RunAccrual.Status.FAILED);
    assertThat(InjectFailure.CALLS.get()).isEqualTo(3);
    assertThat(ledger.count("batch_skip")).isZero();
    assertThat(ledger.count("accrual_entry")).isEqualTo(40);
    EVIDENCE.append(
        """
        -- a stale fencing token at chunk 3: %d writer attempts (no retry), batch_skip rows %d
        (not skipped), step FAILED with %d entries booked

        """
            .formatted(
                InjectFailure.CALLS.get(),
                ledger.count("batch_skip"),
                ledger.count("accrual_entry")));
  }
}
