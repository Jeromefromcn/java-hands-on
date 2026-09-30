package com.jerome.slledger.batch.retention;

import com.jerome.slledger.application.batch.BatchRunMetrics;
import com.jerome.slledger.application.batch.RunRetention;
import com.jerome.slledger.application.messaging.DedupPurge;
import com.jerome.slledger.application.saga.SagaRecovery;
import com.jerome.slledger.batch.support.RunSummaryListener;
import com.jerome.slledger.batch.support.Tasklets;
import java.util.Map;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.DefaultJobParametersValidator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The housekeeping the earlier stages left undone, as a job with one step per table. First it
 * finishes sagas that a stopped process left unfinished (nothing else ever will), then it removes
 * what has outlived its retention: finished sagas and their reservations, terminal TCC rows,
 * published outbox rows, consumed message ids.
 *
 * <p>Recovery comes first so that a saga it finishes now has a fresh terminal entry and is not
 * purged in the same run. Each purge is many bounded transactions ({@code
 * RetentionPolicy.batchSize} rows each), never one that holds locks for the length of the table.
 */
@Configuration(proxyBeanMethods = false)
public class RetentionJobConfiguration {

  public static final String JOB = "retention";

  @Bean
  Job retention(
      JobRepository repository,
      PlatformTransactionManager transactions,
      RunRetention purge,
      SagaRecovery recovery,
      ObjectProvider<DedupPurge> dedup,
      BatchRunMetrics metrics) {
    return new JobBuilder(JOB, repository)
        .validator(
            new DefaultJobParametersValidator(
                new String[] {"businessDate"}, new String[] {"rerun"}))
        .listener(new RunSummaryListener(metrics))
        .start(
            Tasklets.step(
                "recoverSagas",
                repository,
                transactions,
                () -> Map.of("recovered", (long) recovery.recoverAll().size())))
        .next(
            Tasklets.step(
                "purgeSagas", repository, transactions, () -> Map.of("purged", purge.purgeSagas())))
        .next(
            Tasklets.step(
                "purgeTombstones",
                repository,
                transactions,
                () -> Map.of("purged", purge.purgeTombstones())))
        .next(
            Tasklets.step(
                "purgeOutbox",
                repository,
                transactions,
                () -> Map.of("purged", purge.purgeOutbox())))
        .next(
            Tasklets.step(
                "purgeConsumedIds",
                repository,
                transactions,
                () -> {
                  DedupPurge purger = dedup.getIfAvailable();
                  return Map.of("purged", purger == null ? 0L : (long) purger.run());
                }))
        .build();
  }
}
