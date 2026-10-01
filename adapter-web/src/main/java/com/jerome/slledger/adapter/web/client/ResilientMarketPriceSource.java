package com.jerome.slledger.adapter.web.client;

import com.jerome.slledger.application.port.MarketDataRejectedException;
import com.jerome.slledger.application.port.MarketDataUnavailableException;
import com.jerome.slledger.application.port.MarketPriceSource;
import com.jerome.slledger.application.position.UnknownReferenceException;
import com.jerome.slledger.application.quote.OverloadedException;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.money.Money;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The market data source behind a circuit breaker, a bulkhead and a rate limiter.
 *
 * <p>Outside in: breaker, bulkhead, rate limiter, the call. An open breaker refuses before a permit
 * or a token is taken. The bulkhead is outside the limiter so that a call it refuses has not spent
 * a token: the limiter then counts calls that really went to the source. Neither waits: a call that
 * cannot start now is refused now with {@link OverloadedException}, which is the ledger's 503, and
 * is not counted against the source's health because the source did nothing wrong.
 *
 * <p>What counts against the source: it did not answer ({@link MarketDataUnavailableException}), it
 * answered with an error or something unreadable ({@link MarketDataRejectedException}), and an
 * answer that took longer than the slow-call threshold. What does not: "I do not know this
 * security" ({@link UnknownReferenceException}) is an answer, and a stream of them says nothing
 * about the source's health.
 *
 * <p>An open breaker refuses at once with {@link MarketDataUnavailableException}, the exception the
 * rest of the ledger already maps, so nothing above needs to know a breaker exists. There is no
 * retry here: see {@code docs/decisions/12-resilience-observability.md}.
 */
public final class ResilientMarketPriceSource implements MarketPriceSource {

  private static final Logger LOG = LoggerFactory.getLogger(ResilientMarketPriceSource.class);

  private final MarketPriceSource delegate;
  private final CircuitBreaker breaker;
  private final Bulkhead bulkhead;
  private final RateLimiter limiter;

  public ResilientMarketPriceSource(
      MarketPriceSource delegate, CircuitBreaker breaker, Bulkhead bulkhead, RateLimiter limiter) {
    this.delegate = delegate;
    this.breaker = breaker;
    this.bulkhead = bulkhead;
    this.limiter = limiter;
    breaker
        .getEventPublisher()
        .onStateTransition(
            event ->
                LOG.warn(
                    "market data breaker {}: {} -> {} (failure rate {}%, slow call rate {}%, {} calls"
                        + " in window)",
                    event.getCircuitBreakerName(),
                    event.getStateTransition().getFromState(),
                    event.getStateTransition().getToState(),
                    rate(breaker.getMetrics().getFailureRate()),
                    rate(breaker.getMetrics().getSlowCallRate()),
                    breaker.getMetrics().getNumberOfBufferedCalls()));
  }

  /** The breaker configuration for the given properties. */
  public static CircuitBreakerConfig configOf(ResilienceProperties.Breaker p) {
    return CircuitBreakerConfig.custom()
        .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
        .slidingWindowSize(p.windowSize())
        .minimumNumberOfCalls(p.minimumCalls())
        .failureRateThreshold(p.failureRatePercent())
        .slowCallDurationThreshold(p.slowCall())
        .slowCallRateThreshold(p.slowCallRatePercent())
        .waitDurationInOpenState(p.openWait())
        .permittedNumberOfCallsInHalfOpenState(p.halfOpenCalls())
        // Without this the breaker stays open until the next call arrives, and a metric or a log
        // line saying "open" would outlive the outage by however long traffic is quiet.
        .automaticTransitionFromOpenToHalfOpenEnabled(true)
        .recordExceptions(MarketDataUnavailableException.class, MarketDataRejectedException.class)
        .ignoreExceptions(
            UnknownReferenceException.class, BulkheadFullException.class, RequestNotPermitted.class)
        .build();
  }

  public static BulkheadConfig configOf(ResilienceProperties.Bulkhead p) {
    return BulkheadConfig.custom()
        .maxConcurrentCalls(p.maxConcurrent())
        .maxWaitDuration(Duration.ZERO)
        .build();
  }

  public static RateLimiterConfig configOf(ResilienceProperties.RateLimit p) {
    return RateLimiterConfig.custom()
        .limitForPeriod(p.perSecond())
        .limitRefreshPeriod(Duration.ofSeconds(1))
        .timeoutDuration(Duration.ZERO)
        .build();
  }

  @Override
  public Money priceOf(Isin isin) {
    Supplier<Money> call = () -> delegate.priceOf(isin);
    Supplier<Money> limited = RateLimiter.decorateSupplier(limiter, call);
    Supplier<Money> bounded = Bulkhead.decorateSupplier(bulkhead, limited);
    try {
      return breaker.executeSupplier(bounded);
    } catch (CallNotPermittedException open) {
      throw new MarketDataUnavailableException(
          "The market data circuit breaker is "
              + breaker.getState()
              + "; the source was not called",
          open);
    } catch (BulkheadFullException full) {
      throw new OverloadedException(
          "All "
              + bulkhead.getBulkheadConfig().getMaxConcurrentCalls()
              + " concurrent market data calls are in use");
    } catch (RequestNotPermitted limited429) {
      throw new OverloadedException("The market data rate limit of this instance is reached");
    }
  }

  private static String rate(float percent) {
    return percent < 0 ? "n/a" : String.format("%.0f", percent);
  }

  /** For tests and the metrics binder. */
  public CircuitBreaker breaker() {
    return breaker;
  }
}
