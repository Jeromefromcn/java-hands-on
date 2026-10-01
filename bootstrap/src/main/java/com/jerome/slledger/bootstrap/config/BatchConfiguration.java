package com.jerome.slledger.bootstrap.config;

import com.jerome.slledger.application.accrual.AccrualLedger;
import com.jerome.slledger.application.accrual.BookAccruals;
import com.jerome.slledger.application.accrual.RecordSkip;
import com.jerome.slledger.application.accrual.SkippedItems;
import com.jerome.slledger.application.batch.BatchRunMetrics;
import com.jerome.slledger.application.batch.FencedRun;
import com.jerome.slledger.application.batch.Retention;
import com.jerome.slledger.application.batch.RetentionPurges;
import com.jerome.slledger.application.batch.RunRetention;
import com.jerome.slledger.application.batch.RunSummary;
import com.jerome.slledger.application.cache.DistributedLock;
import com.jerome.slledger.application.cache.WriteFence;
import com.jerome.slledger.application.reconciliation.BreakClassifier;
import com.jerome.slledger.application.reconciliation.CustodianHoldings;
import com.jerome.slledger.application.reconciliation.LoadHoldings;
import com.jerome.slledger.application.reconciliation.ReconBreaks;
import com.jerome.slledger.application.reconciliation.SaveBreaks;
import com.jerome.slledger.application.reconciliation.SettlementAttempt;
import com.jerome.slledger.application.reconciliation.SettlementBreaks;
import com.jerome.slledger.application.reconciliation.SettlementGateway;
import com.jerome.slledger.application.reconciliation.SettlementRecovery;
import com.jerome.slledger.application.revaluation.RevaluationDispatcher;
import com.jerome.slledger.batch.accrual.AccrualJobConfiguration;
import com.jerome.slledger.batch.accrual.BatchSettings;
import com.jerome.slledger.batch.accrual.RunAccrual;
import com.jerome.slledger.batch.accrual.RunDailyAccrual;
import com.jerome.slledger.batch.reconciliation.ReconciliationJobConfiguration;
import com.jerome.slledger.batch.reconciliation.RunReconciliation;
import com.jerome.slledger.batch.retention.RetentionJobConfiguration;
import com.jerome.slledger.batch.retention.RunRetentionJob;
import com.jerome.slledger.batch.revaluation.RevaluationJobConfiguration;
import com.jerome.slledger.batch.support.FailurePoint;
import com.jerome.slledger.bootstrap.observability.BatchMeters;
import com.jerome.slledger.bootstrap.observability.MicrometerBatchRunMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.configuration.annotation.EnableJdbcJobRepository;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Spring Batch and the ledger's jobs. Only present when {@code ledger.batch.enabled=true}.
 *
 * <p>The job repository is built here, on the ledger's own {@code DataSource} and transaction
 * manager, and not by Boot's Batch auto-configuration, which is not on the classpath: that one
 * creates the repository whether or not batch is switched on. The schema is Flyway's (V12).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "ledger.batch", name = "enabled", havingValue = "true")
@EnableBatchProcessing
@EnableJdbcJobRepository(dataSourceRef = "dataSource", transactionManagerRef = "transactionManager")
@EnableConfigurationProperties({
  BatchProperties.class,
  ReconciliationProperties.class,
  RetentionProperties.class,
  BatchCommandProperties.class
})
@Import({
  AccrualJobConfiguration.class,
  ReconciliationJobConfiguration.class,
  RetentionJobConfiguration.class
})
public class BatchConfiguration {

  private static final Logger LOG = LoggerFactory.getLogger("com.jerome.slledger.batch.runs");

  @Bean
  BatchRunMetrics batchRunMetrics(MeterRegistry registry) {
    BatchRunMetrics meters = new MicrometerBatchRunMetrics(registry);
    return (RunSummary summary) -> {
      meters.record(summary);
      LOG.info(
          "job={} status={} parameters={} duration={} read={} written={} filtered={} skipped={}"
              + " commits={} rollbacks={}",
          summary.job(),
          summary.status(),
          summary.parameters(),
          summary.duration(),
          summary.read(),
          summary.written(),
          summary.filtered(),
          summary.skipped(),
          summary.commits(),
          summary.rollbacks());
    };
  }

  @Bean
  MeterBinder batchMeters(JdbcClient jdbc) {
    return new BatchMeters(
        jdbc,
        List.of(
            AccrualJobConfiguration.JOB,
            ReconciliationJobConfiguration.JOB,
            RetentionJobConfiguration.JOB,
            RevaluationJobConfiguration.JOB),
        ZoneId.systemDefault());
  }

  @Bean
  BatchSettings batchSettings(BatchProperties properties) {
    return new BatchSettings(
        properties.chunkSize(),
        properties.skipLimit(),
        properties.retryLimit(),
        properties.partitionScheme(),
        properties.partitions(),
        properties.workerThreads());
  }

  @Bean
  BookAccruals bookAccruals(AccrualLedger ledger, WriteFence fence) {
    return new BookAccruals(ledger, fence);
  }

  @Bean
  RecordSkip recordSkip(SkippedItems skipped) {
    return new RecordSkip(skipped);
  }

  @Bean
  RunAccrual runAccrual(
      JobOperator operator, @Qualifier("dailyAccrual") Job dailyAccrual, JobRepository repository) {
    return new RunAccrual(operator, dailyAccrual, repository);
  }

  /**
   * Only with a lock: the cutover must not run on two instances, and without Redis an instance
   * cannot know whether another is running. The lock is the cache configuration's, so it is present
   * exactly when {@code ledger.cache.enabled} is (a property, not a bean condition: a bean
   * condition depends on the order configuration classes are read in).
   */
  @Bean
  @ConditionalOnProperty(prefix = "ledger.cache", name = "enabled", havingValue = "true")
  RunDailyAccrual runDailyAccrual(
      RunAccrual accrual, DistributedLock lock, BatchProperties properties) {
    return new RunDailyAccrual(accrual, new FencedRun(lock, properties.lockTtl()));
  }

  /** Nothing fails on purpose in production; a test supplies its own as {@code @Primary}. */
  @Bean
  FailurePoint failurePoint() {
    return FailurePoint.NONE;
  }

  @Bean
  LoadHoldings loadHoldings(CustodianHoldings holdings) {
    return new LoadHoldings(holdings);
  }

  @Bean
  SaveBreaks saveBreaks(ReconBreaks breaks, Clock clock) {
    return new SaveBreaks(breaks, clock);
  }

  /**
   * No real custodian connection exists in this ledger: every resubmission is answered "no route".
   */
  @Bean
  SettlementGateway settlementGateway() {
    return positionId ->
        new SettlementGateway.Rejected("no automated settlement route is configured");
  }

  @Bean
  SettlementAttempt settlementAttempt(
      SettlementBreaks breaks,
      SettlementGateway gateway,
      Clock clock,
      ReconciliationProperties properties) {
    return new SettlementAttempt(
        breaks, gateway, clock, properties.maxAttempts(), properties.retryDelays());
  }

  @Bean
  SettlementRecovery settlementRecovery(SettlementAttempt attempt) {
    return new SettlementRecovery(attempt);
  }

  @Bean
  BreakClassifier breakClassifier(ReconciliationProperties properties) {
    return new BreakClassifier(properties.policy());
  }

  @Bean
  RunReconciliation runReconciliation(
      JobOperator operator, @Qualifier("reconciliation") Job reconciliation) {
    return new RunReconciliation(operator, reconciliation);
  }

  @Bean
  RetentionPurges retentionPurges(
      Retention retention, Clock clock, RetentionProperties properties, Environment environment) {
    // A cancelled TCC row refuses a Try that arrives late; purged sooner than a message can be
    // redelivered, it would stop refusing it. The topic retention is messaging's property.
    Duration topicRetention =
        environment.getProperty(
            "ledger.messaging.topic-retention", Duration.class, Duration.ofDays(7));
    if (properties.tombstones().compareTo(topicRetention) <= 0) {
      throw new IllegalStateException(
          "ledger.retention.tombstones (%s) must be longer than ledger.messaging.topic-retention (%s)"
              .formatted(properties.tombstones(), topicRetention));
    }
    return new RetentionPurges(retention, clock, properties.policy());
  }

  @Bean
  RunRetention runRetention(RetentionPurges purges) {
    return new RunRetention(purges);
  }

  @Bean
  RunRetentionJob runRetentionJob(JobOperator operator, @Qualifier("retention") Job retention) {
    return new RunRetentionJob(operator, retention);
  }

  /** The timers, only when asked: a batch that starts itself must be switched on for it. */
  @Bean
  @ConditionalOnProperty(prefix = "ledger.batch", name = "schedulers-enabled", havingValue = "true")
  BatchSchedulers batchSchedulers(
      Clock clock,
      ObjectProvider<RunDailyAccrual> fencedAccrual,
      RunAccrual accrual,
      RunRetentionJob retention,
      ObjectProvider<RevaluationDispatcher> dispatcher,
      SettlementRecovery ladder,
      BatchProperties properties) {
    return new BatchSchedulers(
        clock,
        fencedAccrual.getIfAvailable(),
        accrual,
        retention,
        dispatcher.getIfAvailable(() -> null),
        ladder,
        properties.accrualTime(),
        properties.retentionTime(),
        properties.dispatchInterval(),
        properties.ladderInterval());
  }

  /** One job at start-up, for cron and Kubernetes Jobs: only when a job is named. */
  @Bean
  @ConditionalOnProperty(prefix = "ledger.batch.command", name = "job")
  BatchCommandRunner batchCommandRunner(
      BatchCommandProperties command,
      Clock clock,
      ObjectProvider<RunDailyAccrual> fencedAccrual,
      RunAccrual accrual,
      RunReconciliation reconciliation,
      RunRetentionJob retention,
      ConfigurableApplicationContext context) {
    return new BatchCommandRunner(
        command, clock, fencedAccrual, accrual, reconciliation, retention, context);
  }
}
