package com.jerome.slledger.adapter.web.client;

import com.jerome.slledger.application.port.MarketPriceSource;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Puts the protection around the market data client. The decorator is {@code @Primary}, so every
 * consumer of {@link MarketPriceSource} gets it; the raw {@link RestMarketPriceSource} is still a
 * bean, for the decorator and for the tests that are about the unprotected client.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ResilienceProperties.class)
class ResilienceConfiguration {

  static final String BREAKER = "market-data";

  @Bean
  CircuitBreakerRegistry resilienceRegistry() {
    return CircuitBreakerRegistry.ofDefaults();
  }

  @Bean
  BulkheadRegistry bulkheadRegistry() {
    return BulkheadRegistry.ofDefaults();
  }

  @Bean
  RateLimiterRegistry rateLimiterRegistry() {
    return RateLimiterRegistry.ofDefaults();
  }

  @Bean
  @Primary
  @ConditionalOnProperty(
      prefix = "ledger.resilience.marketdata",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  ResilientMarketPriceSource resilientMarketPriceSource(
      RestMarketPriceSource raw,
      ResilienceProperties properties,
      CircuitBreakerRegistry breakers,
      BulkheadRegistry bulkheads,
      RateLimiterRegistry limiters) {
    CircuitBreaker breaker =
        breakers.circuitBreaker(BREAKER, ResilientMarketPriceSource.configOf(properties.breaker()));
    Bulkhead bulkhead =
        bulkheads.bulkhead(BREAKER, ResilientMarketPriceSource.configOf(properties.bulkhead()));
    RateLimiter limiter =
        limiters.rateLimiter(BREAKER, ResilientMarketPriceSource.configOf(properties.rateLimit()));
    return new ResilientMarketPriceSource(raw, breaker, bulkhead, limiter);
  }
}
