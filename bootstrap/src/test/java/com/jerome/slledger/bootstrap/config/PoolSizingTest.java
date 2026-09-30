package com.jerome.slledger.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.bootstrap.demo.tx.support.Evidence;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * {@code ThreadPoolExecutor} adds threads beyond the core size only when the queue refuses an
 * offer. A queue long enough never refuses, so {@code max} is a number nobody reaches. See
 * docs/decisions/08-concurrency.md.
 */
@Timeout(60)
class PoolSizingTest {

  private static final int SUBMITTED = 20;
  private static final StringBuilder EVIDENCE = new StringBuilder();

  @AfterAll
  static void write() {
    Evidence.write(
        "08-pool-sizing.txt",
        """
        Stage 08 · section 6 — core, max and queue length (PoolSizingTest)

        %d tasks that all block are submitted to each configuration (policy=abort).

        ThreadPoolExecutor's rule: below core, a new thread per task; at core, offer to the
        queue; only if the queue refuses the offer, a new thread up to max; then reject.
        A queue that never refuses means max is never used.

        %s"""
            .formatted(SUBMITTED, EVIDENCE));
  }

  @Test
  @DisplayName("core=2 max=8 queue=100: the pool never grows past 2 and the other 18 tasks wait")
  void reproducesPoolNeverGrowingPastCore() throws Exception {
    // See docs/decisions/08-concurrency.md
    PoolStats stats = load(2, 8, 100);

    assertThat(stats.largestPoolSize()).isEqualTo(2);
    assertThat(stats.queued()).isEqualTo(SUBMITTED - 2);
    assertThat(stats.rejected()).isZero();
    record("core=2 max=8 queue=100", stats);
  }

  @Test
  @DisplayName("core=2 max=8 queue=2: the pool grows to 8, then the rest are refused")
  void poolGrowsWithShortQueue() throws Exception {
    PoolStats stats = load(2, 8, 2);

    assertThat(stats.largestPoolSize()).isEqualTo(8);
    assertThat(stats.queued()).isEqualTo(2);
    assertThat(stats.rejected()).isEqualTo(SUBMITTED - 8 - 2);
    record("core=2 max=8 queue=2  ", stats);
  }

  private static void record(String configuration, PoolStats stats) {
    EVIDENCE.append("%s -> %s%n".formatted(configuration, stats));
  }

  private static PoolStats load(int core, int max, int queue) throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    var props =
        new MarketDataExecutorProperties(
            core,
            max,
            queue,
            MarketDataExecutorProperties.Policy.ABORT,
            MarketDataExecutorProperties.Type.PLATFORM,
            1,
            Duration.ofSeconds(3));
    try (MarketDataPool pool = new MarketDataPool(props)) {
      for (int i = 0; i < SUBMITTED; i++) {
        try {
          pool.execute(
              () -> {
                try {
                  release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              });
        } catch (RejectedExecutionException refused) {
          // counted by the pool
        }
      }
      awaitTrue(() -> pool.stats().active() == pool.stats().poolSize());
      PoolStats stats = pool.stats();
      release.countDown();
      return stats;
    }
  }

  private static void awaitTrue(java.util.function.BooleanSupplier condition) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("condition not reached");
      }
      LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
    }
  }
}
