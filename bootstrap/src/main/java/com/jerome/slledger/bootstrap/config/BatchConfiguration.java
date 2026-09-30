package com.jerome.slledger.bootstrap.config;

import com.jerome.slledger.application.batch.BatchRunMetrics;
import com.jerome.slledger.application.batch.RunSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.configuration.annotation.EnableJdbcJobRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
}
