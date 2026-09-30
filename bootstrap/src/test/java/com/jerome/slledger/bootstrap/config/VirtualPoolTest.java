package com.jerome.slledger.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class VirtualPoolTest {

  private static MarketDataExecutorProperties virtual(int maxConcurrent) {
    return new MarketDataExecutorProperties(
        1,
        1,
        1,
        MarketDataExecutorProperties.Policy.ABORT,
        MarketDataExecutorProperties.Type.VIRTUAL,
        maxConcurrent,
        Duration.ofSeconds(3));
  }

  @Test
  @DisplayName("tasks run on virtual threads named market-data-v-N")
  void runsOnVirtualThreads() throws Exception {
    try (MarketDataPool pool = new MarketDataPool(virtual(4))) {
      CompletableFuture<Thread> ran = new CompletableFuture<>();
      pool.execute(() -> ran.complete(Thread.currentThread()));

      Thread thread = ran.get(10, TimeUnit.SECONDS);
      assertThat(thread.isVirtual()).isTrue();
      assertThat(thread.getName()).startsWith("market-data-v-");
    }
  }

  @Test
  @DisplayName("with every slot taken the next task is refused and counted, as abort would")
  void semaphoreRefusesBeyondMaxConcurrent() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch started = new CountDownLatch(2);
    try (MarketDataPool pool = new MarketDataPool(virtual(2))) {
      for (int i = 0; i < 2; i++) {
        pool.execute(
            () -> {
              started.countDown();
              await(release);
            });
      }
      assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();

      assertThatThrownBy(() -> pool.execute(() -> {}))
          .isInstanceOf(RejectedExecutionException.class);
      PoolStats busy = pool.stats();
      release.countDown();

      assertThat(busy.active()).isEqualTo(2);
      assertThat(busy.rejected()).isEqualTo(1);
      assertThat(busy.queued()).isZero();
    }
  }

  @Test
  @DisplayName("a finished task gives its slot back")
  void slotIsReleased() throws Exception {
    try (MarketDataPool pool = new MarketDataPool(virtual(1))) {
      for (int i = 0; i < 5; i++) {
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        while (true) {
          try {
            pool.execute(() -> done.complete(true));
            break;
          } catch (RejectedExecutionException stillReleasing) {
            Thread.onSpinWait();
          }
        }
        assertThat(done.get(10, TimeUnit.SECONDS)).isTrue();
      }
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await(20, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
