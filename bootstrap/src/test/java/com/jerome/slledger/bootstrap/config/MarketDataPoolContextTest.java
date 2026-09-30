package com.jerome.slledger.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.MDC;

@Timeout(30)
class MarketDataPoolContextTest {

  private static MarketDataExecutorProperties props(MarketDataExecutorProperties.Policy policy) {
    return new MarketDataExecutorProperties(
        1, 1, 1, policy, MarketDataExecutorProperties.Type.PLATFORM, 1, Duration.ofSeconds(3));
  }

  @AfterEach
  void reset() {
    MDC.clear();
  }

  @Test
  @DisplayName("a task sees its submitter's trace id, and the worker's MDC is empty afterwards")
  void taskCarriesTraceIdAndLeavesNothing() throws Exception {
    try (MarketDataPool pool =
        new MarketDataPool(props(MarketDataExecutorProperties.Policy.ABORT))) {
      MDC.put("traceId", "request-A");
      var seenInside = new java.util.concurrent.CompletableFuture<String>();
      pool.execute(() -> seenInside.complete(MDC.get("traceId")));
      MDC.clear();
      var seenByNext = new java.util.concurrent.CompletableFuture<String>();
      pool.execute(() -> seenByNext.complete(MDC.get("traceId")));

      assertThat(seenInside.get(10, TimeUnit.SECONDS)).isEqualTo("request-A");
      assertThat(seenByNext.get(10, TimeUnit.SECONDS)).isNull();
    }
  }

  @Test
  @DisplayName(
      "caller-runs: a task run on the submitter's thread does not erase the submitter's MDC")
  void callerRunsKeepsSubmittersMdc() throws Exception {
    java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    try (MarketDataPool pool =
        new MarketDataPool(props(MarketDataExecutorProperties.Policy.CALLER_RUNS))) {
      pool.execute(() -> await(release)); // occupies the worker
      pool.execute(() -> {}); // fills the queue
      MDC.put("traceId", "request-A");
      String[] seen = new String[1];
      pool.execute(() -> seen[0] = MDC.get("traceId")); // refused: runs right here
      release.countDown();

      assertThat(seen[0]).isEqualTo("request-A");
      assertThat(MDC.get("traceId")).isEqualTo("request-A");
    }
  }

  private static void await(java.util.concurrent.CountDownLatch latch) {
    try {
      latch.await(20, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
