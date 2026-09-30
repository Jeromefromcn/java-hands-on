package com.jerome.slledger.bootstrap.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.persistence.testing.Evidence;
import com.jerome.slledger.batch.accrual.RunAccrual;
import com.jerome.slledger.batch.accrual.RunAccrual.Outcome;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.core.env.Environment;

/**
 * The accrual in a child JVM, killed with {@code SIGKILL} in the middle of a chunk. Nothing runs
 * after a kill -9: no shutdown hook, no listener, no status update. What the tables say afterwards
 * is what a real crash leaves, and it is not what an exception leaves.
 *
 * <p>Elapsed real time is the subject only in that the child has to boot; the test waits on a
 * marker file with a bound.
 */
@Timeout(600)
class HardKillRestartIT {

  @Test
  void aKilledJobIsStartedNotFailed_refusedOnLaunch_thenRecoveredAndResumed() throws Exception {
    try (BatchLedger ledger = BatchLedger.start("--ledger.batch.chunk-size=10")) {
      ledger.seed(100);
      Environment environment = ledger.app.getEnvironment();
      Path marker = Files.createTempFile("hardkill", ".marker");
      Files.delete(marker);
      Path log = Files.createTempFile("hardkill-child", ".log");

      Process child =
          new ProcessBuilder(
                  Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                  "-cp",
                  System.getProperty("java.class.path"),
                  HardKillMain.class.getName(),
                  environment.getProperty("spring.datasource.url"),
                  environment.getProperty("spring.datasource.username"),
                  environment.getProperty("spring.datasource.password"),
                  marker.toString(),
                  "4",
                  BatchLedger.DAY.toString())
              .redirectErrorStream(true)
              .redirectOutput(log.toFile())
              .start();
      try {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(240);
        while (!Files.exists(marker)) {
          if (!child.isAlive() || System.nanoTime() > deadline) {
            throw new AssertionError(
                "The child did not reach chunk 4. Its output:\n" + Files.readString(log));
          }
          Thread.sleep(100);
        }
        child.destroyForcibly();
        assertThat(child.waitFor(30, TimeUnit.SECONDS)).isTrue();
        assertThat(child.exitValue()).isEqualTo(137); // 128 + SIGKILL
      } finally {
        child.destroyForcibly();
      }

      // What a kill leaves: an execution that says STARTED, with no end time and no failure.
      assertThat(ledger.count("accrual_entry")).isEqualTo(30);
      String afterKill = executionTable(ledger) + "\n" + ledger.stepTableOfLastExecution();
      assertThat(
              ledger
                  .jdbc
                  .sql(
                      "SELECT status || '/' || coalesce(end_time::text, 'no end time')"
                          + " FROM batch_job_execution")
                  .query(String.class)
                  .single())
          .isEqualTo("STARTED/no end time");

      // A launch is refused: as far as the repository knows, the first run is still going.
      RunAccrual accrual = ledger.bean(RunAccrual.class);
      Outcome refused = accrual.run(BatchLedger.DAY);
      assertThat(refused.status()).isEqualTo(RunAccrual.Status.ALREADY_RUNNING);

      // The operator has established the process is gone and says so; then the launch resumes it.
      JobRepository repository = ledger.bean(JobRepository.class);
      JobOperator operator = ledger.bean(JobOperator.class);
      JobExecution stuck = repository.getJobExecution(lastExecutionId(ledger));
      JobExecution recovered = operator.recover(stuck);
      String afterRecover = executionTable(ledger) + "\n" + ledger.stepTableOfLastExecution();

      Outcome resumed = accrual.run(BatchLedger.DAY);

      assertThat(resumed.status()).isEqualTo(RunAccrual.Status.COMPLETED);
      assertThat(resumed.execution().getJobInstanceId()).isEqualTo(recovered.getJobInstanceId());
      assertThat(ledger.count("accrual_entry")).isEqualTo(100);
      assertThat(
              ledger
                  .jdbc
                  .sql("SELECT count(DISTINCT position_id) FROM accrual_entry")
                  .query(Long.class)
                  .single())
          .isEqualTo(100L);
      assertThat(ledger.stepContextLong(resumed.execution().getId(), "accrual", "accrual.inserted"))
          .isEqualTo(100L);
      String afterResume = executionTable(ledger) + "\n" + ledger.stepTableOfLastExecution();
      Evidence.write(
          "11-hard-kill.txt",
          """
          Stage 11, section 5: the accrual for 2026-09-30 (100 positions, chunk size 10) runs in a
          child JVM that stops inside chunk 4's transaction and is then killed with SIGKILL
          (exit value %d = 128 + 9). Real Postgres; every table below is BATCH_* metadata.

          -- after the kill (accrual_entry rows: 30, chunks 1-3; chunk 4 was never committed)
          %s
          -- a launch of the same date: %s
          -- after JobOperator.recover on the stuck execution
          %s
          -- after the second launch: %s (accrual_entry rows: %d, inserted in total: 100, the resumed run's share 70)
          %s
          """
              .formatted(
                  137,
                  afterKill,
                  refused.status(),
                  afterRecover,
                  resumed.status(),
                  ledger.count("accrual_entry"),
                  afterResume));
    }
  }

  private static long lastExecutionId(BatchLedger ledger) {
    return ledger
        .jdbc
        .sql("SELECT max(job_execution_id) FROM batch_job_execution")
        .query(Long.class)
        .single();
  }

  private static String executionTable(BatchLedger ledger) {
    StringBuilder table =
        new StringBuilder("job_execution_id | instance | status | exit_code | end_time\n");
    ledger
        .jdbc
        .sql(
            "SELECT job_execution_id, job_instance_id, status, exit_code, end_time"
                + " FROM batch_job_execution ORDER BY job_execution_id")
        .query(
            (rs, n) -> {
              table
                  .append(rs.getLong(1))
                  .append(" | ")
                  .append(rs.getLong(2))
                  .append(" | ")
                  .append(rs.getString(3))
                  .append(" | ")
                  .append(rs.getString(4))
                  .append(" | ")
                  .append(rs.getString(5))
                  .append('\n');
              return null;
            })
        .list();
    return table.toString();
  }
}
