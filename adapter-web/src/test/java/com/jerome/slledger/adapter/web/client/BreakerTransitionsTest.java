package com.jerome.slledger.adapter.web.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.jerome.slledger.application.port.MarketDataRejectedException;
import com.jerome.slledger.application.port.MarketDataUnavailableException;
import com.jerome.slledger.application.port.MarketPriceSource;
import com.jerome.slledger.application.position.UnknownReferenceException;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.money.Money;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.ratelimiter.RateLimiter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The breaker against a delegate the test controls. The open wait is real time (200 ms): the
 * transition out of OPEN is the library's scheduler, which is what is being exercised.
 */
@Timeout(30)
class BreakerTransitionsTest {

  private static final Isin ISIN = new Isin("HK0000000007");
  private static final Money PRICE =
      new Money(new BigDecimal("41.30"), Currency.getInstance("HKD"));

  /** What the delegate does on its next call. */
  private final AtomicReference<java.util.function.Supplier<Money>> behaviour =
      new AtomicReference<>(() -> PRICE);

  private final AtomicInteger delegateCalls = new AtomicInteger();
  private final List<String> transitions = new ArrayList<>();
  private final List<String> log = new ArrayList<>();
  private ResilientMarketPriceSource source;
  private CircuitBreaker breaker;

  private static ResilienceProperties.Breaker props(Duration slow, float slowRate) {
    return new ResilienceProperties.Breaker(10, 5, 50, slow, slowRate, Duration.ofMillis(200), 2);
  }

  private void build(ResilienceProperties.Breaker props) {
    MarketPriceSource delegate =
        isin -> {
          delegateCalls.incrementAndGet();
          return behaviour.get().get();
        };
    breaker = CircuitBreaker.of("market-data", ResilientMarketPriceSource.configOf(props));
    breaker
        .getEventPublisher()
        .onStateTransition(
            e -> {
              String line =
                  "%s  %s -> %s  (failure rate %.0f%%, slow rate %.0f%%, %d calls buffered)"
                      .formatted(
                          Instant.now(),
                          e.getStateTransition().getFromState(),
                          e.getStateTransition().getToState(),
                          breaker.getMetrics().getFailureRate(),
                          breaker.getMetrics().getSlowCallRate(),
                          breaker.getMetrics().getNumberOfBufferedCalls());
              log.add(line);
              transitions.add(
                  e.getStateTransition().getFromState()
                      + "->"
                      + e.getStateTransition().getToState());
            });
    source =
        new ResilientMarketPriceSource(
            delegate,
            breaker,
            Bulkhead.of(
                "market-data",
                ResilientMarketPriceSource.configOf(new ResilienceProperties.Bulkhead(100))),
            RateLimiter.of(
                "market-data",
                ResilientMarketPriceSource.configOf(new ResilienceProperties.RateLimit(100_000))));
  }

  @BeforeEach
  void fresh() {
    build(props(Duration.ofSeconds(1), 100));
  }

  @Test
  @DisplayName(
      "CLOSED -> OPEN on failures, refusing without a call; HALF_OPEN after the wait; CLOSED")
  void closedOpenHalfOpenClosed() throws IOException {
    behaviour.set(
        () -> {
          throw new MarketDataUnavailableException("down", null);
        });

    for (int i = 0; i < 5; i++) {
      assertThatThrownBy(() -> source.priceOf(ISIN))
          .isInstanceOf(MarketDataUnavailableException.class);
    }
    assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    int callsWhenOpened = delegateCalls.get();

    assertThatThrownBy(() -> source.priceOf(ISIN))
        .isInstanceOf(MarketDataUnavailableException.class)
        .hasMessageContaining("OPEN");
    assertThat(delegateCalls.get())
        .as("an open breaker does not call the source")
        .isEqualTo(callsWhenOpened);

    await()
        .atMost(Duration.ofSeconds(5))
        .until(() -> breaker.getState() == CircuitBreaker.State.HALF_OPEN);

    behaviour.set(() -> PRICE);
    assertThat(source.priceOf(ISIN)).isEqualTo(PRICE);
    assertThat(source.priceOf(ISIN)).isEqualTo(PRICE);

    assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    assertThat(transitions).containsExactly("CLOSED->OPEN", "OPEN->HALF_OPEN", "HALF_OPEN->CLOSED");
    write("12-breaker-transitions.txt", log);
  }

  @Test
  @DisplayName("a probe that fails in HALF_OPEN opens it again")
  void halfOpenFailureReopens() {
    behaviour.set(
        () -> {
          throw new MarketDataRejectedException("500");
        });
    for (int i = 0; i < 5; i++) {
      assertThatThrownBy(() -> source.priceOf(ISIN))
          .isInstanceOf(MarketDataRejectedException.class);
    }
    await()
        .atMost(Duration.ofSeconds(5))
        .until(() -> breaker.getState() == CircuitBreaker.State.HALF_OPEN);

    assertThatThrownBy(() -> source.priceOf(ISIN)).isInstanceOf(MarketDataRejectedException.class);
    assertThatThrownBy(() -> source.priceOf(ISIN)).isInstanceOf(MarketDataRejectedException.class);

    assertThat(transitions).startsWith("CLOSED->OPEN", "OPEN->HALF_OPEN", "HALF_OPEN->OPEN");
  }

  @Test
  @DisplayName("a stream of 'unknown security' answers leaves the breaker closed")
  void unknownSecurityIsNotAFailure() {
    behaviour.set(
        () -> {
          throw UnknownReferenceException.subject("security", ISIN.value());
        });

    for (int i = 0; i < 50; i++) {
      assertThatThrownBy(() -> source.priceOf(ISIN)).isInstanceOf(UnknownReferenceException.class);
    }

    assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
    assertThat(delegateCalls.get()).isEqualTo(50);
  }

  @Test
  @DisplayName("slow answers open it by the slow-call rate even though every call succeeds")
  void slowCallsOpenIt() {
    build(props(Duration.ofMillis(20), 50));
    behaviour.set(
        () -> {
          try {
            Thread.sleep(40); // real time: the call's duration is what is classified
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
          return PRICE;
        });

    for (int i = 0; i < 5; i++) {
      assertThat(source.priceOf(ISIN)).isEqualTo(PRICE);
    }

    assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
  }

  private static void write(String name, List<String> lines) throws IOException {
    Path dir = Path.of("target", "evidence");
    Files.createDirectories(dir);
    Files.writeString(dir.resolve(name), String.join("\n", lines) + "\n");
  }
}
