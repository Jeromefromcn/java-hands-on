package com.jerome.slledger.bootstrap.config;

import com.jerome.slledger.application.messaging.Outbox;
import com.jerome.slledger.application.revaluation.MarginCallMarkers;
import com.jerome.slledger.application.revaluation.RaiseMarginCalls;
import com.jerome.slledger.application.revaluation.RevaluationDispatcher;
import com.jerome.slledger.application.revaluation.RevaluationLauncher;
import com.jerome.slledger.application.revaluation.RevaluationRequests;
import com.jerome.slledger.application.revaluation.Revalue;
import com.jerome.slledger.batch.revaluation.RevaluationJobConfiguration;
import com.jerome.slledger.batch.revaluation.RunRevaluation;
import java.time.Clock;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The revaluation job and its dispatcher. Only present when batch is on <em>and</em> messaging is:
 * a margin call is an outbox row, and without the outbox there is nowhere to raise it. With this
 * absent, {@code ApplyPriceUpdate} revalues inline whatever the size of the security.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "ledger",
    name = {"batch.enabled", "messaging.enabled"},
    havingValue = "true")
@Import(RevaluationJobConfiguration.class)
public class RevaluationConfiguration {

  @Bean
  Revalue revalue(Clock clock) {
    return new Revalue(clock);
  }

  @Bean
  RaiseMarginCalls raiseMarginCalls(MarginCallMarkers markers, Outbox outbox) {
    return new RaiseMarginCalls(markers, outbox);
  }

  @Bean
  RevaluationLauncher runRevaluation(JobOperator operator, @Qualifier("revaluation") Job job) {
    return new RunRevaluation(operator, job);
  }

  @Bean
  RevaluationDispatcher revaluationDispatcher(
      RevaluationRequests requests,
      RevaluationLauncher launcher,
      @Value("${ledger.revaluation.max-attempts:5}") int maxAttempts) {
    return new RevaluationDispatcher(requests, launcher, maxAttempts);
  }
}
