package com.jerome.slledger.bootstrap.batch;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.bootstrap.position.LedgerApp;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The job repository is the ledger's own database, and it exists only when batch is switched on.
 */
@Timeout(180)
class BatchWiringIT {

  /** One step that does nothing, so the repository's rows are all there is to see. */
  static class OneStepJob {

    @Bean
    Job noop(JobRepository repository, PlatformTransactionManager transactions) {
      return new JobBuilder("noop", repository)
          .start(
              new StepBuilder("noop-step", repository)
                  .tasklet((contribution, context) -> RepeatStatus.FINISHED, transactions)
                  .build())
          .build();
    }
  }

  @Test
  @DisplayName("with ledger.batch.enabled a job runs and its history is in the ledger's database")
  void aJobLeavesItsHistoryInTheLedgerDatabase() throws Exception {
    try (ConfigurableApplicationContext app =
        LedgerApp.start(List.of(OneStepJob.class), "--ledger.batch.enabled=true")) {
      JobOperator operator = app.getBean(JobOperator.class);
      JdbcClient jdbc = app.getBean(JdbcClient.class);

      JobExecution execution =
          operator.start(
              app.getBean("noop", Job.class),
              new JobParametersBuilder().addString("day", "d1").toJobParameters());

      assertThat(execution.getStatus().name()).isEqualTo("COMPLETED");
      assertThat(count(jdbc, "batch_job_instance")).isEqualTo(1);
      assertThat(count(jdbc, "batch_job_execution")).isEqualTo(1);
      assertThat(
              jdbc.sql("SELECT status FROM batch_step_execution WHERE step_name = 'noop-step'")
                  .query(String.class)
                  .single())
          .isEqualTo("COMPLETED");
    }
  }

  @Test
  @DisplayName("with ledger.batch.enabled unset there is no job repository and no operator")
  void nothingExistsWhenOff() {
    try (ConfigurableApplicationContext app = LedgerApp.start()) {
      assertThat(app.getBeansOfType(JobRepository.class)).isEmpty();
      assertThat(app.getBeansOfType(JobOperator.class)).isEmpty();
    }
  }

  private static long count(JdbcClient jdbc, String table) {
    return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
  }
}
