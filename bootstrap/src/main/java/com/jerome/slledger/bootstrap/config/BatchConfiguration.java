package com.jerome.slledger.bootstrap.config;

import com.jerome.slledger.application.accrual.AccrualLedger;
import com.jerome.slledger.application.accrual.BookAccruals;
import com.jerome.slledger.application.accrual.RecordSkip;
import com.jerome.slledger.application.accrual.SkippedItems;
import com.jerome.slledger.application.batch.BatchRunMetrics;
import com.jerome.slledger.application.batch.FencedRun;
import com.jerome.slledger.application.batch.RunSummary;
import com.jerome.slledger.application.cache.DistributedLock;
import com.jerome.slledger.application.cache.WriteFence;
import com.jerome.slledger.batch.accrual.AccrualJobConfiguration;
import com.jerome.slledger.batch.accrual.BatchSettings;
import com.jerome.slledger.batch.accrual.RunAccrual;
import com.jerome.slledger.batch.accrual.RunDailyAccrual;
import com.jerome.slledger.batch.support.FailurePoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.configuration.annotation.EnableJdbcJobRepository;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

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
@EnableConfigurationProperties(BatchProperties.class)
@Import(AccrualJobConfiguration.class)
public class BatchConfiguration {

  private static final Logger LOG = LoggerFactory.getLogger("com.jerome.slledger.batch.runs");

  @Bean
  BatchRunMetrics batchRunMetrics() {
    return (RunSummary summary) ->
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
}
