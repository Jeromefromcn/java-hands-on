package com.jerome.slledger.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jerome.slledger.adapter.web.client.MarketDataProperties;
import com.jerome.slledger.adapter.web.client.ResilienceProperties;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TimeoutBudgetTest {

  private static final URI URL = URI.create("http://127.0.0.1:8089");

  private static MarketDataExecutorProperties executor(Duration deadline) {
    return new MarketDataExecutorProperties(
        4,
        8,
        16,
        MarketDataExecutorProperties.Policy.ABORT,
        MarketDataExecutorProperties.Type.PLATFORM,
        32,
        deadline);
  }

  private static ResilienceProperties resilience(Duration slowCall) {
    return new ResilienceProperties(
        true,
        new ResilienceProperties.Breaker(20, 10, 50, slowCall, 80, Duration.ofSeconds(10), 3),
        new ResilienceProperties.Bulkhead(32),
        new ResilienceProperties.RateLimit(200));
  }

  private static TimeoutBudget budget(
      Duration edge, Duration deadline, Duration connect, Duration read, Duration slowCall) {
    return new TimeoutBudget(
        new TimeoutProperties(edge, Duration.ofSeconds(10), Duration.ofSeconds(3), true),
        new MarketDataProperties(URL, connect, read),
        executor(deadline),
        resilience(slowCall));
  }

  @Test
  @DisplayName("the defaults of application.yml hold, and strictly decrease along each chain")
  void defaultsHold() {
    TimeoutBudget budget =
        budget(
            Duration.ofSeconds(30),
            Duration.ofSeconds(3),
            Duration.ofMillis(500),
            Duration.ofSeconds(2),
            Duration.ofSeconds(1));

    assertThat(budget.problems()).isEmpty();
    budget.verify();
    assertThat(budget.table()).contains("GET /quotes").contains("fan-out deadline");
  }

  @Test
  @DisplayName(
      "an inner layer that can take as long as the one above it is refused, naming the chain")
  void outerNotLongerThanInnerIsRefused() {
    // The fan-out gives up at 2 s while a source call may take 2.5 s.
    TimeoutBudget budget =
        budget(
            Duration.ofSeconds(30),
            Duration.ofSeconds(2),
            Duration.ofMillis(500),
            Duration.ofSeconds(2),
            Duration.ofSeconds(1));

    assertThatThrownBy(budget::verify)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("GET /quotes")
        .hasMessageContaining("fan-out deadline");
  }

  @Test
  @DisplayName("equal is refused: the outer layer must outlive the inner, not tie with it")
  void equalIsRefused() {
    TimeoutBudget budget =
        budget(
            Duration.ofSeconds(30),
            Duration.ofMillis(2500),
            Duration.ofMillis(500),
            Duration.ofSeconds(2),
            Duration.ofSeconds(1));

    assertThat(budget.problems()).anyMatch(p -> p.contains("fan-out deadline"));
  }

  @Test
  @DisplayName("a read timeout of zero is an unbounded wait and breaks every chain it is in")
  void unboundedIsRefused() {
    TimeoutBudget budget =
        budget(
            Duration.ofSeconds(30),
            Duration.ofSeconds(3),
            Duration.ofMillis(500),
            Duration.ZERO,
            Duration.ofSeconds(1));

    assertThat(budget.problems()).hasSizeGreaterThanOrEqualTo(2);
    assertThat(budget.table()).contains("unbounded");
  }

  @Test
  @DisplayName("a breaker slow-call threshold at or above the read timeout can never fire")
  void slowCallThresholdAboveReadTimeout() {
    TimeoutBudget budget =
        budget(
            Duration.ofSeconds(30),
            Duration.ofSeconds(3),
            Duration.ofMillis(500),
            Duration.ofSeconds(2),
            Duration.ofSeconds(2));

    assertThat(budget.problems()).anyMatch(p -> p.contains("slow-call"));
  }
}
