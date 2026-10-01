package com.jerome.slledger.adapter.web.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jerome.slledger.application.port.MarketPriceSource;
import com.jerome.slledger.application.quote.OverloadedException;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.money.Money;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class BulkheadAndLimiterTest {

  private static final Isin ISIN = new Isin("HK0000000007");
  private static final Money PRICE =
      new Money(new BigDecimal("41.30"), Currency.getInstance("HKD"));

  private static final ResilienceProperties.Breaker BREAKER =
      new ResilienceProperties.Breaker(
          10, 5, 50, Duration.ofSeconds(1), 100, Duration.ofSeconds(10), 2);

  private static ResilientMarketPriceSource source(
      MarketPriceSource delegate, int concurrent, int perSecond, CircuitBreaker breaker) {
    return new ResilientMarketPriceSource(
        delegate,
        breaker,
        Bulkhead.of(
            "b",
            ResilientMarketPriceSource.configOf(new ResilienceProperties.Bulkhead(concurrent))),
        RateLimiter.of(
            "r",
            ResilientMarketPriceSource.configOf(new ResilienceProperties.RateLimit(perSecond))));
  }

  private static CircuitBreaker breaker() {
    return CircuitBreaker.of("b", ResilientMarketPriceSource.configOf(BREAKER));
  }

  @Test
  @DisplayName("calls beyond the bulkhead are refused at once, not queued behind the slow ones")
  void bulkheadRefusesBeyondTheLimit() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger inside = new AtomicInteger();
    MarketPriceSource slow =
        isin -> {
          inside.incrementAndGet();
          try {
            release.await(20, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return PRICE;
        };
    ResilientMarketPriceSource source = source(slow, 3, 100_000, breaker());
    ExecutorService pool = Executors.newFixedThreadPool(3);
    try {
      List<Future<Money>> running = new ArrayList<>();
      for (int i = 0; i < 3; i++) {
        running.add(pool.submit(() -> source.priceOf(ISIN)));
      }
      while (inside.get() < 3) {
        Thread.onSpinWait();
      }

      long start = System.nanoTime();
      assertThatThrownBy(() -> source.priceOf(ISIN))
          .isInstanceOf(OverloadedException.class)
          .hasMessageContaining("3 concurrent");
      assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(200));
      assertThat(inside.get()).as("the refused call never reached the source").isEqualTo(3);

      release.countDown();
      for (Future<Money> call : running) {
        assertThat(call.get(10, TimeUnit.SECONDS)).isEqualTo(PRICE);
      }
      assertThat(source.priceOf(ISIN)).as("the slots are free again").isEqualTo(PRICE);
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  @DisplayName("the rate limiter refuses the calls beyond the rate within the second")
  void rateLimiterRefusesBeyondTheRate() {
    AtomicInteger reached = new AtomicInteger();
    ResilientMarketPriceSource source =
        source(
            isin -> {
              reached.incrementAndGet();
              return PRICE;
            },
            100,
            5,
            breaker());

    int refused = 0;
    for (int i = 0; i < 12; i++) {
      try {
        source.priceOf(ISIN);
      } catch (OverloadedException e) {
        refused++;
      }
    }

    assertThat(reached.get()).isEqualTo(5);
    assertThat(refused).isEqualTo(7);
  }

  @Test
  @DisplayName("refusals of the bulkhead and the limiter are ours: they never open the breaker")
  void ownRefusalsDoNotOpenTheBreaker() {
    CircuitBreaker breaker = breaker();
    ResilientMarketPriceSource source = source(isin -> PRICE, 100, 1, breaker);

    int refused = 0;
    for (int i = 0; i < 60; i++) {
      try {
        source.priceOf(ISIN);
      } catch (OverloadedException e) {
        refused++;
      }
    }

    assertThat(refused).isEqualTo(59);
    assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
  }

  @Test
  @DisplayName("an open breaker refuses before taking a bulkhead slot or a rate token")
  void openBreakerSpendsNothing() {
    CircuitBreaker breaker = breaker();
    breaker.transitionToOpenState();
    RateLimiter limiter =
        RateLimiter.of(
            "r", ResilientMarketPriceSource.configOf(new ResilienceProperties.RateLimit(1)));
    Bulkhead bulkhead =
        Bulkhead.of("b", ResilientMarketPriceSource.configOf(new ResilienceProperties.Bulkhead(1)));
    ResilientMarketPriceSource source =
        new ResilientMarketPriceSource(isin -> PRICE, breaker, bulkhead, limiter);

    for (int i = 0; i < 5; i++) {
      assertThatThrownBy(() -> source.priceOf(ISIN))
          .isInstanceOf(com.jerome.slledger.application.port.MarketDataUnavailableException.class);
    }

    assertThat(limiter.getMetrics().getAvailablePermissions()).isEqualTo(1);
    assertThat(bulkhead.getMetrics().getAvailableConcurrentCalls()).isEqualTo(1);
  }
}
