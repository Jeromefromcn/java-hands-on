package com.jerome.slledger.bootstrap.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.persistence.testing.Evidence;
import com.jerome.slledger.application.batch.BatchRunMetrics;
import com.jerome.slledger.application.batch.RunSummary;
import com.jerome.slledger.batch.accrual.RunAccrual;
import com.jerome.slledger.batch.accrual.RunAccrual.Outcome;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The accrual job on the application's real wiring: one entry per active position, in chunks; a
 * date runs once, however many times it is launched; a back-dated date is its own instance.
 */
@Timeout(300)
class AccrualJobIT {

  /** Replaces the log-only metrics so a test can read what the listener reported. */
  static class RecordingMetrics {

    static final List<RunSummary> SUMMARIES = new CopyOnWriteArrayList<>();

    @Bean
    @Primary
    BatchRunMetrics recordingMetrics() {
      return SUMMARIES::add;
    }
  }

  private static BatchLedger ledger;
  private static RunAccrual accrual;
  private static final StringBuilder IDEMPOTENT = new StringBuilder();
  private static final StringBuilder INSTANCES = new StringBuilder();

  @BeforeAll
  static void start() {
    ledger = BatchLedger.start(List.of(RecordingMetrics.class), "--ledger.batch.chunk-size=10");
    accrual = ledger.bean(RunAccrual.class);
  }

  @AfterAll
  static void stop() {
    Evidence.write("11-idempotent-rerun.txt", IDEMPOTENT.toString());
    Evidence.write("11-job-instances.txt", INSTANCES.toString());
    ledger.close();
  }

  @BeforeEach
  void fresh() {
    ledger.resetLedger();
    ledger.resetBatchMetadata();
    RecordingMetrics.SUMMARIES.clear();
    ledger.seed(25);
  }

  private long inserted(long jobExecutionId) {
    return ledger.stepContextLong(jobExecutionId, "accrual", "accrual.inserted");
  }

  private long present(long jobExecutionId) {
    return ledger.stepContextLong(jobExecutionId, "accrual", "accrual.alreadyPresent");
  }

  @Test
  @DisplayName("one entry per active position, booked in ceil(N / chunk) commits, cursor saved")
  void booksOneEntryPerActivePosition() {
    Outcome outcome = accrual.run(BatchLedger.DAY);

    assertThat(outcome.status()).isEqualTo(RunAccrual.Status.COMPLETED);
    assertThat(ledger.count("accrual_entry")).isEqualTo(25);
    assertThat(
            ledger
                .jdbc
                .sql(
                    "SELECT count(DISTINCT position_id) FROM accrual_entry WHERE business_date = ?")
                .param(BatchLedger.DAY)
                .query(Long.class)
                .single())
        .isEqualTo(25);
    long execution = outcome.execution().getId();
    assertThat(
            ledger
                .jdbc
                .sql(
                    "SELECT read_count, write_count, commit_count FROM batch_step_execution"
                        + " WHERE job_execution_id = ?")
                .param(execution)
                .query((rs, n) -> List.of(rs.getLong(1), rs.getLong(2), rs.getLong(3)))
                .single())
        .containsExactly(25L, 25L, 3L);
    assertThat(inserted(execution)).isEqualTo(25);
    assertThat(present(execution)).isZero();
    assertThat(ledger.stepContext(execution, "accrual").getString("accrual.lastKey"))
        .isEqualTo("P000025");
  }

  @Test
  @DisplayName("the fee booked is the domain's: 1,000 x 41.26 x 3.65% / 365, rounded once")
  void feeIsTheDomainsFee() {
    accrual.run(BatchLedger.DAY);

    assertThat(
            ledger
                .jdbc
                .sql("SELECT DISTINCT fee_amount::text || ' ' || fee_currency FROM accrual_entry")
                .query(String.class)
                .single())
        .isEqualTo("4.130000 HKD");
  }

  @Test
  @DisplayName("launching a completed date again is answered ALREADY_DONE: no run, no new rows")
  void secondLaunchOfADateIsSkipped() {
    Outcome first = accrual.run(BatchLedger.DAY);
    Outcome second = accrual.run(BatchLedger.DAY);

    assertThat(first.status()).isEqualTo(RunAccrual.Status.COMPLETED);
    assertThat(second.status()).isEqualTo(RunAccrual.Status.ALREADY_DONE);
    assertThat(ledger.count("accrual_entry")).isEqualTo(25);
    assertThat(ledger.count("batch_job_execution")).isEqualTo(1);
    IDEMPOTENT.append(
        """
        Stage 11, section 4: the same business date launched twice (25 active positions).
        Real Postgres; the job repository is the ledger's own database.

        first launch  : %s, %d entries booked (inserted %d, already present %d)
        second launch : %s, batch_job_execution rows = %d, accrual_entry rows = %d
        """
            .formatted(
                first.status(),
                25,
                inserted(first.execution().getId()),
                present(first.execution().getId()),
                second.status(),
                ledger.count("batch_job_execution"),
                ledger.count("accrual_entry")));
  }

  @Test
  @DisplayName("a rerun instance of a booked date books nothing and reports every row as present")
  void rerunInstanceIsRefusedByThePrimaryKey() {
    accrual.run(BatchLedger.DAY);

    Outcome rerun = accrual.run(BatchLedger.DAY, OptionalLong.of(1), new JobParametersBuilder());

    assertThat(rerun.status()).isEqualTo(RunAccrual.Status.COMPLETED);
    assertThat(ledger.count("accrual_entry")).isEqualTo(25);
    assertThat(ledger.count("batch_job_instance")).isEqualTo(2);
    assertThat(inserted(rerun.execution().getId())).isZero();
    assertThat(present(rerun.execution().getId())).isEqualTo(25);
    IDEMPOTENT.append(
        """

        a second instance of the same date (identifying rerun=1):
        rerun         : %s, inserted %d, already present %d, accrual_entry rows = %d,
                        batch_job_instance rows = %d
        """
            .formatted(
                rerun.status(),
                inserted(rerun.execution().getId()),
                present(rerun.execution().getId()),
                ledger.count("accrual_entry"),
                ledger.count("batch_job_instance")));
  }

  @Test
  @DisplayName("a back-dated run is its own job instance, and the two dates do not collide")
  void backDatedRunIsADistinctInstance() {
    Outcome today = accrual.run(BatchLedger.DAY);
    Outcome yesterday = accrual.run(BatchLedger.DAY.minusDays(1));

    assertThat(yesterday.status()).isEqualTo(RunAccrual.Status.COMPLETED);
    assertThat(yesterday.execution().getJobInstanceId())
        .isNotEqualTo(today.execution().getJobInstanceId());
    assertThat(ledger.count("accrual_entry")).isEqualTo(50);
    assertThat(ledger.count("batch_job_instance")).isEqualTo(2);
    assertThat(accrual.run(BatchLedger.DAY.minusDays(1)).status())
        .isEqualTo(RunAccrual.Status.ALREADY_DONE);
    INSTANCES.append(
        """
        Stage 11, section 4: job instances are identified by their identifying parameters.

        """);
    ledger
        .jdbc
        .sql(
            "SELECT i.job_instance_id, i.job_name, p.parameter_name, p.parameter_type,"
                + " p.parameter_value, p.identifying FROM batch_job_instance i"
                + " JOIN batch_job_execution e USING (job_instance_id)"
                + " JOIN batch_job_execution_params p USING (job_execution_id)"
                + " ORDER BY i.job_instance_id")
        .query(
            (rs, n) -> {
              INSTANCES
                  .append("instance ")
                  .append(rs.getLong(1))
                  .append("  ")
                  .append(rs.getString(2))
                  .append("  ")
                  .append(rs.getString(3))
                  .append(" (")
                  .append(rs.getString(4))
                  .append(") = ")
                  .append(rs.getString(5))
                  .append("  identifying=")
                  .append(rs.getString(6))
                  .append('\n');
              return null;
            })
        .list();
  }

  @Test
  @DisplayName("the listener reports a summary of a completed run")
  void summaryIsRecorded() {
    accrual.run(BatchLedger.DAY);

    assertThat(RecordingMetrics.SUMMARIES).hasSize(1);
    RunSummary summary = RecordingMetrics.SUMMARIES.getFirst();
    assertThat(summary.job()).isEqualTo("dailyAccrual");
    assertThat(summary.status()).isEqualTo("COMPLETED");
    assertThat(summary.read()).isEqualTo(25);
    assertThat(summary.written()).isEqualTo(25);
    assertThat(summary.skipped()).isZero();
    assertThat(summary.commits()).isEqualTo(3);
    assertThat(summary.parameters()).containsEntry("businessDate", BatchLedger.DAY.toString());
  }
}
