package com.jerome.slledger.bootstrap.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.persistence.testing.Evidence;
import com.jerome.slledger.application.accrual.AccrualCandidate;
import com.jerome.slledger.application.accrual.AccrualCandidates;
import com.jerome.slledger.application.accrual.AccrualLedger;
import com.jerome.slledger.application.accrual.AccrueDay;
import com.jerome.slledger.application.accrual.Slice;
import com.jerome.slledger.batch.accrual.RunAccrual;
import com.jerome.slledger.batch.accrual.RunAccrual.Outcome;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stage 5 kept a bad position from failing the whole accrual with an outer transaction and one
 * {@code REQUIRES_NEW} per position. Spring Batch's skip is the framework's answer to the same
 * symptom. This test runs both on the same 200 positions, five of them corrupt, and counts what
 * each does, to answer whether they solve the same problem.
 *
 * <p>The {@code REQUIRES_NEW} version is test code and is kept only as the comparison.
 */
@Timeout(300)
class SkipVersusRequiresNewIT {

  private static final StringBuilder EVIDENCE =
      new StringBuilder(
          """
          Stage 11, section 6: Spring Batch skip against stage 5's REQUIRES_NEW loop.
          Same ledger for both: 200 active positions, five of them corrupt (P000010, P000050,
          P000090, P000130, P000170). Real Postgres. "Write transactions" is the difference in
          pg_current_xact_id() around each run: every transaction that writes consumes one id.

          """);

  private static BatchLedger ledger;
  private static RunAccrual accrual;

  @BeforeAll
  static void start() {
    ledger =
        BatchLedger.start(
            List.of(FailAt.class), "--ledger.batch.chunk-size=20", "--ledger.batch.skip-limit=10");
    accrual = ledger.bean(RunAccrual.class);
  }

  @AfterAll
  static void stop() {
    Evidence.write("11-skip-vs-requires-new.txt", EVIDENCE.toString());
    ledger.close();
  }

  @BeforeEach
  void fresh() {
    FailAt.never();
    ledger.resetLedger();
    ledger.resetBatchMetadata();
    ledger.jdbc.sql("DELETE FROM batch_skip").update();
    ledger.seed(200);
    for (String id : List.of("P000010", "P000050", "P000090", "P000130", "P000170")) {
      ledger
          .jdbc
          .sql("UPDATE loan_position SET rule_type = 'FLOATING' WHERE id = ?")
          .param(id)
          .update();
    }
  }

  /** Stage 5's shape: an outer transaction with a run row, and one REQUIRES_NEW per position. */
  private record LoopResult(int booked, int skipped, long millis) {}

  private LoopResult requiresNewLoop(LocalDate day, int failAtPosition) {
    AccrualCandidates candidates = ledger.bean(AccrualCandidates.class);
    AccrualLedger book = ledger.bean(AccrualLedger.class);
    JdbcClient jdbc = ledger.jdbc;
    PlatformTransactionManager manager = ledger.bean(PlatformTransactionManager.class);
    TransactionTemplate outer = new TransactionTemplate(manager);
    TransactionTemplate inner = new TransactionTemplate(manager);
    inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    int[] counts = new int[2];
    long start = System.nanoTime();
    outer.executeWithoutResult(
        status -> {
          jdbc.sql(
                  "INSERT INTO batch_skip (job, scope, item_key, phase, reason)"
                      + " VALUES ('requiresNewLoop', ?, 'RUN', 'run', 'started')")
              .param(day.toString())
              .update();
          Optional<String> after = Optional.empty();
          int seen = 0;
          while (true) {
            List<AccrualCandidate> page = candidates.page(day, Slice.ALL, after, 50);
            for (AccrualCandidate candidate : page) {
              if (++seen == failAtPosition) {
                throw new IllegalStateException("injected: the process is going down");
              }
              try {
                inner.executeWithoutResult(
                    s -> book.book(List.of(AccrueDay.accrue(candidate, day))));
                counts[0]++;
              } catch (RuntimeException refused) {
                counts[1]++;
              }
            }
            if (page.size() < 50) {
              break;
            }
            after = Optional.of(page.getLast().positionId());
          }
        });
    return new LoopResult(counts[0], counts[1], (System.nanoTime() - start) / 1_000_000);
  }

  /**
   * The next transaction id. Every transaction that writes consumes one, so the difference between
   * two readings, less the reading's own, is the number of write transactions in between (commits
   * and rollbacks alike). {@code pg_stat_database.xact_commit} would count all transactions but is
   * flushed by each backend only when it has been idle for some seconds, so it lags a run.
   */
  private long xid() {
    return ledger.jdbc.sql("SELECT pg_current_xact_id()::text::bigint").query(Long.class).single();
  }

  @Test
  @DisplayName("both keep going past five bad rows; the batch does it in a tenth of the commits")
  void bothSkipTheBadRows() throws Exception {
    long beforeBatch = xid();
    long batchStart = System.nanoTime();
    Outcome outcome = accrual.run(BatchLedger.DAY);
    long batchMillis = (System.nanoTime() - batchStart) / 1_000_000;
    long afterBatch = xid();
    assertThat(outcome.status()).isEqualTo(RunAccrual.Status.COMPLETED);
    long batchEntries = ledger.count("accrual_entry");
    long batchWrites = afterBatch - beforeBatch - 1;
    String batchStep = ledger.stepTable(outcome.execution().getId());
    long batchSkipRows = ledger.count("batch_skip");

    ledger.resetLedger();
    ledger.seed(200);
    for (String id : List.of("P000010", "P000050", "P000090", "P000130", "P000170")) {
      ledger
          .jdbc
          .sql("UPDATE loan_position SET rule_type = 'FLOATING' WHERE id = ?")
          .param(id)
          .update();
    }
    long beforeLoop = xid();
    LoopResult loop = requiresNewLoop(BatchLedger.DAY, -1);
    long afterLoop = xid();

    assertThat(loop.booked()).isEqualTo(195);
    assertThat(loop.skipped()).isEqualTo(5);
    assertThat(ledger.count("accrual_entry")).isEqualTo(195);
    long loopWrites = afterLoop - beforeLoop - 1;
    assertThat(loopWrites).isGreaterThan(batchWrites * 3);
    // The loop records nothing about the five it skipped; the batch wrote each to batch_skip.
    assertThat(batchSkipRows).isEqualTo(5L);
    EVIDENCE.append(
        """
        -- the same 200 positions, five corrupt
        Spring Batch skip (chunks of 20)  : %d entries, %d write transactions (chunks, job
                                            repository updates and skip records included),
                                            %d ms, 5 skips recorded in batch_skip
        REQUIRES_NEW loop (one per row)   : %d entries, %d write transactions, %d ms, skips
                                            recorded: none (a counter in the caller)
        Spring Batch step row:
        %s
        """
            .formatted(
                batchEntries,
                batchWrites,
                batchMillis,
                loop.booked(),
                loopWrites,
                loop.millis(),
                batchStep));
  }

  @Test
  @DisplayName(
      "when the process fails midway the loop leaves committed rows and no record; the batch a cursor")
  void whatEachLeavesWhenTheRunFailsMidway() {
    // The batch: a failure in chunk 6, after five chunks (100 rows, five of them skipped)
    // committed.
    FailAt.on(6);
    Outcome outcome = accrual.run(BatchLedger.DAY);
    assertThat(outcome.status()).isEqualTo(RunAccrual.Status.FAILED);
    long batchEntries = ledger.count("accrual_entry");
    String batchState = ledger.stepTable(outcome.execution().getId());
    Object cursor =
        ledger.stepContext(outcome.execution().getId(), "accrual").getString("accrual.lastKey");

    // The loop: a failure at the 100th row, outside any per-row transaction.
    ledger.resetLedger();
    ledger.seed(200);
    for (String id : List.of("P000010", "P000050", "P000090", "P000130", "P000170")) {
      ledger
          .jdbc
          .sql("UPDATE loan_position SET rule_type = 'FLOATING' WHERE id = ?")
          .param(id)
          .update();
    }
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> requiresNewLoop(BatchLedger.DAY, 100))
        .isInstanceOf(IllegalStateException.class);
    long loopEntries = ledger.count("accrual_entry");
    long loopRunRows =
        ledger
            .jdbc
            .sql("SELECT count(*) FROM batch_skip WHERE job = 'requiresNewLoop'")
            .query(Long.class)
            .single();

    assertThat(loopEntries).isEqualTo(96); // 99 rows before the 100th, three corrupt among them
    assertThat(loopRunRows).isZero(); // the outer transaction, and its run row, rolled back
    assertThat(batchEntries).isEqualTo(97); // P000001..P000100 less three corrupt
    assertThat(cursor).isEqualTo("P000100");
    EVIDENCE.append(
        """

        -- each run fails midway (the batch in chunk 6, the loop at its 100th row)
        REQUIRES_NEW loop : %d entries committed; run rows left by the outer transaction: %d
                            (rolled back with it: nothing says a run started, where it stopped, or
                            what it skipped; a rerun starts from the first row)
        Spring Batch      : %d entries committed; the repository says:
        %s
                            saved cursor: %s; a launch of the same date resumes after it
        """
            .formatted(loopEntries, loopRunRows, batchEntries, batchState, cursor));
  }
}
