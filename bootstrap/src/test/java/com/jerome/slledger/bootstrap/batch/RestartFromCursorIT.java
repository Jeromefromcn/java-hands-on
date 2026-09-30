package com.jerome.slledger.bootstrap.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.persistence.testing.Evidence;
import com.jerome.slledger.application.accrual.AccrualCandidate;
import com.jerome.slledger.application.accrual.AccrualCandidates;
import com.jerome.slledger.application.accrual.Slice;
import com.jerome.slledger.batch.accrual.BatchSettings;
import com.jerome.slledger.batch.accrual.RunAccrual;
import com.jerome.slledger.batch.accrual.RunAccrual.Outcome;
import com.jerome.slledger.batch.support.KeysetReader;
import com.jerome.slledger.domain.accrual.AccrualEntry;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.batch.infrastructure.item.ItemStreamReader;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * A job that fails at a chosen chunk and is launched again. The evidence is the {@code
 * BATCH_STEP_EXECUTION} table and the saved context, not the fact that the second launch said
 * COMPLETED.
 */
@Timeout(300)
class RestartFromCursorIT {

  /** The same processor and writer as the product, behind a reader that keeps no place. */
  static class NoCursorJob {

    @Bean
    @StepScope
    ItemStreamReader<AccrualCandidate> noCursorReader(
        AccrualCandidates candidates,
        BatchSettings settings,
        @Value("#{jobParameters['businessDate']}") LocalDate businessDate) {
      KeysetReader<AccrualCandidate> inner =
          new KeysetReader<>(
              "nocursor",
              settings.chunkSize(),
              after -> candidates.page(businessDate, Slice.ALL, after, settings.chunkSize()),
              AccrualCandidate::positionId);
      return new ItemStreamReader<>() {
        @Override
        public AccrualCandidate read() {
          return inner.read();
        }

        @Override
        public void open(ExecutionContext context) {
          inner.open(new ExecutionContext());
        }

        @Override
        public void update(ExecutionContext context) {}

        @Override
        public void close() {
          inner.close();
        }
      };
    }

    @Bean
    Job noCursorAccrual(
        JobRepository repository,
        PlatformTransactionManager transactions,
        BatchSettings settings,
        @Qualifier("noCursorReader") ItemStreamReader<AccrualCandidate> reader,
        ItemProcessor<AccrualCandidate, AccrualEntry> accrualProcessor,
        ItemWriter<AccrualEntry> accrualWriter) {
      return new JobBuilder("noCursorAccrual", repository)
          .start(
              new StepBuilder("accrual", repository)
                      .<AccrualCandidate, AccrualEntry>chunk(settings.chunkSize())
                      .transactionManager(transactions)
                      .reader(reader)
                      .processor(accrualProcessor)
                      .writer(accrualWriter)
                      .stream(reader)
                      .build())
          .build();
    }
  }

  private static BatchLedger ledger;
  private static RunAccrual accrual;
  private static final StringBuilder RESTART = new StringBuilder();

  @BeforeAll
  static void start() {
    ledger =
        BatchLedger.start(List.of(FailAt.class, NoCursorJob.class), "--ledger.batch.chunk-size=10");
    accrual = ledger.bean(RunAccrual.class);
  }

  @AfterAll
  static void stop() {
    Evidence.write("11-restart-step-table.txt", RESTART.toString());
    ledger.close();
  }

  @BeforeEach
  void fresh() {
    FailAt.never();
    ledger.resetLedger();
    ledger.resetBatchMetadata();
    ledger.seed(100);
  }

  private String lastKey(long jobExecutionId) {
    return ledger.stepContext(jobExecutionId, "accrual").getString("accrual.lastKey");
  }

  @Test
  @DisplayName(
      "a job failed at chunk 4 resumes after the last committed chunk, and reads only the rest")
  void resumesFromTheSavedCursor() {
    FailAt.on(4);
    Outcome first = accrual.run(BatchLedger.DAY);

    assertThat(first.status()).isEqualTo(RunAccrual.Status.FAILED);
    assertThat(ledger.count("accrual_entry")).isEqualTo(30);
    String failedTable = ledger.stepTable(first.execution().getId());
    assertThat(lastKey(first.execution().getId())).isEqualTo("P000030");
    assertThat(
            ledger
                .jdbc
                .sql("SELECT status FROM batch_job_execution WHERE job_execution_id = ?")
                .param(first.execution().getId())
                .query(String.class)
                .single())
        .isEqualTo("FAILED");

    // One position sorts before the saved key, one after it; both arrive while the job is down.
    ledger
        .fixtures
        .position("P000015X", "CP-001", "HK0000000001", 1_000)
        .borrowTradesForAll("P000015X", BatchLedger.SETTLED);
    ledger
        .fixtures
        .position("P000090X", "CP-001", "HK0000000001", 1_000)
        .borrowTradesForAll("P000090X", BatchLedger.SETTLED);

    Outcome second = accrual.run(BatchLedger.DAY);

    assertThat(second.status()).isEqualTo(RunAccrual.Status.COMPLETED);
    assertThat(second.execution().getJobInstanceId())
        .isEqualTo(first.execution().getJobInstanceId());
    assertThat(second.execution().getId()).isNotEqualTo(first.execution().getId());
    String resumedTable = ledger.stepTable(second.execution().getId());
    // Rows after P000030: 70 seeded plus P000090X. P000015X is behind the cursor.
    assertThat(
            ledger
                .jdbc
                .sql("SELECT read_count FROM batch_step_execution WHERE job_execution_id = ?")
                .param(second.execution().getId())
                .query(Long.class)
                .single())
        .isEqualTo(71L);
    // The running totals continue across executions of one instance: 30 booked by the failed
    // execution, 71 by this one.
    assertThat(ledger.stepContextLong(second.execution().getId(), "accrual", "accrual.inserted"))
        .isEqualTo(101L);
    assertThat(
            ledger.stepContextLong(second.execution().getId(), "accrual", "accrual.alreadyPresent"))
        .isZero();
    assertThat(ledger.count("accrual_entry")).isEqualTo(101);
    assertThat(
            ledger
                .jdbc
                .sql("SELECT count(*) FROM accrual_entry WHERE position_id = 'P000015X'")
                .query(Long.class)
                .single())
        .isZero();
    assertThat(
            ledger
                .jdbc
                .sql("SELECT count(DISTINCT position_id) FROM accrual_entry")
                .query(Long.class)
                .single())
        .isEqualTo(101L);
    RESTART.append(
        """
        Stage 11, section 5: the accrual for 2026-09-30, 100 active positions, chunk size 10.
        The writer is made to fail once, at chunk 4 (chunks 1-3 have committed). Then two
        positions are added while the job is down: P000015X sorts before the saved key,
        P000090X after it. The job is launched again with the same business date.
        Real Postgres; rows below are read from BATCH_STEP_EXECUTION.

        -- first execution (job_execution_id %d), after the failure
        %s
        saved context after the failure: %s
        accrual_entry rows after the failure: 30

        -- second execution (job_execution_id %d), same job instance %d
        %s
        accrual_entry rows at the end: %d (100 seeded + P000090X; P000015X is behind the cursor
        and is not booked by the resumed run: a rerun instance books it, see 11-idempotent-rerun)
        """
            .formatted(
                first.execution().getId(),
                failedTable,
                lastKey(first.execution().getId()),
                second.execution().getId(),
                second.execution().getJobInstanceId(),
                resumedTable,
                ledger.count("accrual_entry")));
  }

  @Test
  @DisplayName("a rerun instance books the position that was behind the cursor, and nothing else")
  void rerunPicksUpWhatTheCursorMissed() {
    FailAt.on(4);
    accrual.run(BatchLedger.DAY);
    ledger
        .fixtures
        .position("P000015X", "CP-001", "HK0000000001", 1_000)
        .borrowTradesForAll("P000015X", BatchLedger.SETTLED);
    accrual.run(BatchLedger.DAY);

    Outcome rerun =
        accrual.run(
            BatchLedger.DAY,
            java.util.OptionalLong.of(1),
            new org.springframework.batch.core.job.parameters.JobParametersBuilder());

    assertThat(rerun.status()).isEqualTo(RunAccrual.Status.COMPLETED);
    assertThat(ledger.count("accrual_entry")).isEqualTo(101);
    assertThat(ledger.stepContextLong(rerun.execution().getId(), "accrual", "accrual.inserted"))
        .isEqualTo(1L);
    assertThat(
            ledger.stepContextLong(rerun.execution().getId(), "accrual", "accrual.alreadyPresent"))
        .isEqualTo(100L);
  }

  @Test
  @DisplayName("reproducesRestartFromTheBeginningWithoutACursor")
  void reproducesRestartFromTheBeginningWithoutACursor() throws Exception {
    // See docs/decisions/11-batch.md
    // The same job, the same failure, and a reader that saves no place. The entries are right (the
    // primary key refuses the second booking of the first 30) and the run is wrong in cost: it
    // reads and writes everything again.
    FailAt.on(4);
    JobOperator operator = ledger.bean(JobOperator.class);
    Job job = ledger.app.getBean("noCursorAccrual", Job.class);
    var params =
        new org.springframework.batch.core.job.parameters.JobParametersBuilder()
            .addLocalDate("businessDate", BatchLedger.DAY)
            .toJobParameters();

    var first = operator.run(job, params);
    var second = operator.run(job, params);

    assertThat(first.getStatus().name()).isEqualTo("FAILED");
    assertThat(second.getStatus().name()).isEqualTo("COMPLETED");
    assertThat(ledger.count("accrual_entry")).isEqualTo(100);
    long read =
        ledger
            .jdbc
            .sql("SELECT read_count FROM batch_step_execution WHERE job_execution_id = ?")
            .param(second.getId())
            .query(Long.class)
            .single();
    assertThat(read).isEqualTo(100L);
    assertThat(ledger.stepContextLong(second.getId(), "accrual", "accrual.alreadyPresent"))
        .isEqualTo(30L);
    RESTART.append(
        """

        -- reproducesRestartFromTheBeginningWithoutACursor (same failure at chunk 4)
        second execution with a reader that saves no key:
        %s
        entries booked in total: %d (correct: the primary key refused the first 30 again)
        but the resumed run read all 100 rows and found 30 already present.
        """
            .formatted(ledger.stepTable(second.getId()), ledger.count("accrual_entry")));
  }
}
