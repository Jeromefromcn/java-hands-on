package com.jerome.slledger.bootstrap.observability;

import com.jerome.slledger.application.observability.LedgerMetrics;
import com.jerome.slledger.application.observability.Tracing;
import com.jerome.slledger.bootstrap.config.MarketDataPool;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedBulkheadMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.github.resilience4j.micrometer.tagged.TaggedRateLimiterMetrics;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The meters that exist whatever is switched on. Meters that need Kafka or Batch are declared next
 * to the configuration that creates what they read.
 */
@Configuration(proxyBeanMethods = false)
class ObservabilityConfiguration {

  @Bean
  LedgerMetrics ledgerMetrics(MeterRegistry registry) {
    return new MicrometerLedgerMetrics(registry);
  }

  /**
   * The tracer's port, or {@code Tracing.NONE} when there is no tracer. Not a
   * {@code @ConditionalOnBean}: this class is read before the tracing auto-configuration has
   * registered the tracer, so such a condition would always be false here.
   */
  @Bean
  Tracing tracing(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
    Tracer t = tracer.getIfAvailable();
    Propagator p = propagator.getIfAvailable();
    return t == null || p == null ? Tracing.NONE : new MicrometerTracing(t, p);
  }

  @Bean
  MeterBinder circuitBreakerMeters(CircuitBreakerRegistry registry) {
    return TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry);
  }

  @Bean
  MeterBinder bulkheadMeters(BulkheadRegistry registry) {
    return TaggedBulkheadMetrics.ofBulkheadRegistry(registry);
  }

  @Bean
  MeterBinder rateLimiterMeters(RateLimiterRegistry registry) {
    return TaggedRateLimiterMetrics.ofRateLimiterRegistry(registry);
  }

  @Bean
  MeterBinder marketDataPoolMeters(MarketDataPool pool) {
    return new PoolMeters(pool);
  }
}
