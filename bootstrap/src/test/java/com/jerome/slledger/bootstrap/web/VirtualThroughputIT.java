package com.jerome.slledger.bootstrap.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.bootstrap.config.MarketDataExecutorProperties;
import com.jerome.slledger.bootstrap.config.MarketDataPool;
import com.jerome.slledger.bootstrap.demo.tx.support.Evidence;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The same blocking workload on the platform pool and on the virtual executor. Each task waits
 * {@value #CALL_MILLIS} ms, as a call to a slow source does, and returns. Run under {@code
 * -Pmeasure}; the numbers go to {@code docs/evidence/08-virtual-throughput.txt}. See
 * docs/decisions/08-concurrency.md.
 */
@Tag("measurement")
@Timeout(300)
class VirtualThroughputIT {

  private static final int TASKS = 400;
  private static final int CALL_MILLIS = 100;
  private static final int ROUNDS = 3;

  @Test
  void platformPoolAgainstVirtualExecutor() throws Exception {
    StringBuilder out =
        new StringBuilder(
            """
            Stage 08 · section 8 — platform pool against virtual executor (VirtualThroughputIT)

            %d tasks per round, each blocking %d ms (a slow call to the source). %d rounds each.
            Platform: core=max=8, queue=%d. Virtual: max-concurrent=%d (the semaphore is the
            only bound). JDK %s, %d CPUs.

            """
                .formatted(
                    TASKS,
                    CALL_MILLIS,
                    ROUNDS,
                    TASKS,
                    TASKS,
                    System.getProperty("java.version"),
                    Runtime.getRuntime().availableProcessors()));
    long platformBest = Long.MAX_VALUE;
    long virtualBest = Long.MAX_VALUE;
    for (int round = 1; round <= ROUNDS; round++) {
      long platform = run(MarketDataExecutorProperties.Type.PLATFORM, 8, 8, TASKS, 8);
      long virtual = run(MarketDataExecutorProperties.Type.VIRTUAL, 1, 1, 1, TASKS);
      out.append(
          "round %d: platform %5d ms (%4d calls/s)   virtual %5d ms (%5d calls/s)%n"
              .formatted(round, platform, perSecond(platform), virtual, perSecond(virtual)));
      platformBest = Math.min(platformBest, platform);
      virtualBest = Math.min(virtualBest, virtual);
    }
    out.append(
        "%nbest of %d: platform %d ms, virtual %d ms (%.1fx)%n"
            .formatted(ROUNDS, platformBest, virtualBest, (double) platformBest / virtualBest));
    Evidence.write("08-virtual-throughput.txt", out.toString());

    assertThat(virtualBest).isLessThan(platformBest);
  }

  private static long perSecond(long millis) {
    return TASKS * 1000L / millis;
  }

  private static long run(
      MarketDataExecutorProperties.Type type, int core, int max, int queue, int maxConcurrent)
      throws Exception {
    var props =
        new MarketDataExecutorProperties(
            core,
            max,
            queue,
            MarketDataExecutorProperties.Policy.ABORT,
            type,
            maxConcurrent,
            Duration.ofSeconds(3));
    try (MarketDataPool pool = new MarketDataPool(props)) {
      List<CompletableFuture<Void>> all = new ArrayList<>();
      long started = System.nanoTime();
      for (int i = 0; i < TASKS; i++) {
        all.add(
            CompletableFuture.runAsync(
                () -> LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(CALL_MILLIS)), pool));
      }
      CompletableFuture.allOf(all.toArray(CompletableFuture[]::new)).get(120, TimeUnit.SECONDS);
      return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }
  }
}
