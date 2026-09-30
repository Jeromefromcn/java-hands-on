package com.jerome.slledger.bootstrap.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The executor that fans price requests out to the market data source. Size, queue and rejection
 * policy are properties so that the bean and the experiments in {@code
 * docs/decisions/08-concurrency.md} are governed by the same configuration.
 *
 * <p>An invalid combination refuses startup. A queue larger than the pool is legal, and is exactly
 * the configuration in which {@code max} is never used; that is shown, not forbidden.
 */
@Validated
@ConfigurationProperties(prefix = "ledger.marketdata.executor")
public record MarketDataExecutorProperties(
    @DefaultValue("4") @Min(1) int core,
    @DefaultValue("8") @Min(1) int max,
    @DefaultValue("16") @Min(1) int queue,
    @DefaultValue("abort") @NotNull Policy policy,
    @DefaultValue("platform") @NotNull Type type,
    @DefaultValue("32") @Min(1) int maxConcurrent) {

  public enum Policy {
    ABORT,
    CALLER_RUNS,
    DISCARD,
    DISCARD_OLDEST
  }

  public enum Type {
    PLATFORM,
    VIRTUAL
  }

  @AssertTrue(message = "max must not be below core")
  public boolean isMaxAtLeastCore() {
    return max >= core;
  }
}
