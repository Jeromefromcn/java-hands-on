package com.jerome.slledger.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@Timeout(30)
class MarketDataExecutorConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(MarketDataExecutorConfiguration.class);

  @Test
  @DisplayName("defaults bind and build a pool of the documented shape")
  void defaults() {
    runner.run(
        context -> {
          assertThat(context).hasNotFailed();
          assertThat(context.getBean(MarketDataExecutorProperties.class))
              .isEqualTo(
                  new MarketDataExecutorProperties(
                      4,
                      8,
                      16,
                      MarketDataExecutorProperties.Policy.ABORT,
                      MarketDataExecutorProperties.Type.PLATFORM,
                      32));
        });
  }

  @Test
  @DisplayName("max below core refuses to start, naming the rule")
  void rejectsMaxBelowCore() {
    runner
        .withPropertyValues("ledger.marketdata.executor.core=4", "ledger.marketdata.executor.max=2")
        .run(
            context ->
                assertThat(rootMessages(context.getStartupFailure())).contains("maxAtLeastCore"));
  }

  @Test
  @DisplayName("an empty queue refuses to start")
  void rejectsZeroQueue() {
    runner
        .withPropertyValues("ledger.marketdata.executor.queue=0")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  @DisplayName("workers are named market-data-N, and a forced rejection is counted")
  void namesThreadsAndCountsRejections() throws Exception {
    MarketDataExecutorProperties props =
        new MarketDataExecutorProperties(
            1,
            1,
            1,
            MarketDataExecutorProperties.Policy.ABORT,
            MarketDataExecutorProperties.Type.PLATFORM,
            1);
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch started = new CountDownLatch(1);
    AtomicReference<String> name = new AtomicReference<>();
    try (MarketDataPool pool = new MarketDataPool(props)) {
      pool.execute(
          () -> {
            name.set(Thread.currentThread().getName());
            started.countDown();
            await(release);
          });
      assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
      pool.execute(() -> {}); // fills the queue
      try {
        pool.execute(() -> {});
        throw new AssertionError("expected a rejection");
      } catch (RejectedExecutionException expected) {
        // counted below
      } finally {
        release.countDown();
      }
      assertThat(name.get()).startsWith("market-data-");
      assertThat(pool.stats().rejected()).isEqualTo(1);
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await(20, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static String rootMessages(Throwable failure) {
    StringBuilder all = new StringBuilder();
    for (Throwable t = failure; t != null; t = t.getCause()) {
      all.append(t.getMessage()).append('\n');
    }
    return all.toString();
  }
}
