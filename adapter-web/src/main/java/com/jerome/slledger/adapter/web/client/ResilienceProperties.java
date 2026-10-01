package com.jerome.slledger.adapter.web.client;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How the market data source is protected. {@code enabled=false} is an operator's kill switch: the
 * raw client is used, with only the timeouts of {@link MarketDataProperties}. It exists so that the
 * stage 7 and 8 reproductions, which are about the unprotected client, still run.
 *
 * <p>The breaker's defaults are justified in {@code docs/decisions/12-resilience-observability.md}
 * from the measured latency of a healthy source; every value is a property so a test can shrink a
 * window.
 */
@ConfigurationProperties("ledger.resilience.marketdata")
public record ResilienceProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue Breaker breaker,
    @DefaultValue Bulkhead bulkhead,
    @DefaultValue RateLimit rateLimit) {

  /**
   * At most this many calls to the source are in flight; the next is refused at once, not queued. A
   * queue here would be the unbounded wait the bulkhead exists to remove, moved one step in.
   */
  public record Bulkhead(@DefaultValue("32") int maxConcurrent) {}

  /** At most this many calls to the source start in any second; the next is refused at once. */
  public record RateLimit(@DefaultValue("200") int perSecond) {}

  /**
   * @param windowSize how many of the latest calls the rates are computed over
   * @param minimumCalls the rates mean nothing below this many calls; the breaker stays closed
   * @param failureRatePercent open when at least this share of the window failed
   * @param slowCall a call slower than this counts as slow, answered or not
   * @param slowCallRatePercent open when at least this share of the window was slow
   * @param openWait how long it refuses calls before it lets a few through
   * @param halfOpenCalls how many probe calls decide between closing and opening again
   */
  public record Breaker(
      @DefaultValue("20") int windowSize,
      @DefaultValue("10") int minimumCalls,
      @DefaultValue("50") float failureRatePercent,
      @DefaultValue("1s") Duration slowCall,
      @DefaultValue("80") float slowCallRatePercent,
      @DefaultValue("10s") Duration openWait,
      @DefaultValue("3") int halfOpenCalls) {}
}
