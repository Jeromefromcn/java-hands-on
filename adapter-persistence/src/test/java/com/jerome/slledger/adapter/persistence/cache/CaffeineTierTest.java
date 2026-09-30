package com.jerome.slledger.adapter.persistence.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.jerome.slledger.adapter.persistence.testing.Evidence;
import com.jerome.slledger.application.cache.CacheTier;
import com.jerome.slledger.application.cache.Entry;
import com.jerome.slledger.application.cache.ReadThrough;
import com.jerome.slledger.application.cache.TtlPolicy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class CaffeineTierTest {

  private final AtomicLong nanos = new AtomicLong();

  @Test
  void entryExpiresAtItsOwnTtl() {
    CaffeineTier<String, String> tier = new CaffeineTier<>(100, nanos::get);
    tier.put("short", Entry.present("s"), Duration.ofSeconds(1));
    tier.put("long", Entry.present("l"), Duration.ofSeconds(10));

    nanos.addAndGet(Duration.ofSeconds(2).toNanos());

    assertThat(tier.get("short")).isEmpty();
    assertThat(tier.get("long")).contains(Entry.present("l"));
  }

  @Test
  void absentEntryRoundTrips() {
    CaffeineTier<String, String> tier = new CaffeineTier<>(100, nanos::get);
    tier.put("k", Entry.absent(), Duration.ofSeconds(1));

    assertThat(tier.get("k")).contains(Entry.absent());
  }

  @Test
  void reproducesUnboundedGrowth() {
    // See docs/decisions/09-caching.md
    int keys = 100_000;
    String payload = "x".repeat(100);

    long before = usedHeapBytes();
    var unbounded = Caffeine.newBuilder().build();
    for (int i = 0; i < keys; i++) {
      unbounded.put("SYN" + i, payload + i);
    }
    long unboundedSize = unbounded.estimatedSize();
    long unboundedHeap = usedHeapBytes() - before;
    unbounded.invalidateAll();
    unbounded.cleanUp();

    before = usedHeapBytes();
    CaffeineTier<String, String> bounded = new CaffeineTier<>(1_000, nanos::get);
    for (int i = 0; i < keys; i++) {
      bounded.put("SYN" + i, Entry.present(payload + i), Duration.ofMinutes(10));
    }
    bounded.cleanUp();
    long boundedHeap = usedHeapBytes() - before;

    assertThat(unboundedSize).isEqualTo(keys);
    assertThat(bounded.size()).isLessThanOrEqualTo(1_000);

    Evidence.write(
        "09-l1-growth.txt",
        """
        Stage 9, section 3: an unbounded Caffeine cache against CaffeineTier with maximumSize=1000.
        %d distinct keys, each with a 100-character value. Measured on this machine, JDK %s.
        Heap is used memory after System.gc(), minus used memory before the cache existed.

        unbounded : entries=%d, heap growth=%d bytes
        bounded   : entries<=%d, heap growth=%d bytes

        Stage 15's heap experiment reuses the unbounded builder above: raise `keys` until the
        JVM's -Xmx is reached and the OutOfMemoryError is the finding.
        """
            .formatted(
                keys,
                System.getProperty("java.version"),
                unboundedSize,
                unboundedHeap,
                bounded.size(),
                boundedHeap));
  }

  @Test
  void reproducesAvalancheWithFixedTtl() {
    // See docs/decisions/09-caching.md
    int keys = 1_000;
    Duration ttl = Duration.ofSeconds(1);
    Duration step = Duration.ofMillis(50);
    int steps = 60;

    int[] fixed = loadsPerStep(keys, new TtlPolicy(ttl, 0, ttl), step, steps);
    int[] jittered = loadsPerStep(keys, new TtlPolicy(ttl, 0.2, ttl), step, steps);

    int fixedPeak = max(fixed);
    int jitteredPeak = max(jittered);
    assertThat(fixedPeak).isEqualTo(keys);
    assertThat(jitteredPeak).isLessThan(keys / 2);

    StringBuilder out = new StringBuilder();
    out.append(
        """
        Stage 9, section 3: origin loads per 50 ms step, %d keys stored at t=0 with a 1 s TTL,
        every key read at every step, 3 s of controlled time. The ticker is driven by the test, so
        these counts are deterministic by construction: they measure the mechanism, not this
        machine's clock. The loads are the calls the source would have received.

        peak per step : fixed TTL = %d, jitter 0.2 = %d
        total loads   : fixed TTL = %d, jitter 0.2 = %d

        step   t(ms)   fixed TTL                jitter 0.2
        """
            .formatted(keys, fixedPeak, jitteredPeak, sum(fixed), sum(jittered)));
    for (int i = 0; i < steps; i++) {
      out.append(
          "%3d  %6d   %5d %-20s %5d %s%n"
              .formatted(
                  i + 1,
                  (i + 1) * step.toMillis(),
                  fixed[i],
                  "#".repeat(fixed[i] / 50),
                  jittered[i],
                  "#".repeat(jittered[i] / 50)));
    }
    Evidence.write("09-avalanche.txt", out.toString());
  }

  private static int[] loadsPerStep(int keys, TtlPolicy policy, Duration step, int steps) {
    AtomicLong clock = new AtomicLong();
    CacheTier<String, String> tier = new CaffeineTier<>(keys * 2L, clock::get);
    int[] loadsNow = new int[1];
    ReadThrough<String, String> cache =
        new ReadThrough<>(
            List.of(new ReadThrough.Layer<>(tier, policy)),
            key -> {
              loadsNow[0]++;
              return Optional.of("price");
            },
            new Random(2026));
    List<String> all = new ArrayList<>();
    for (int i = 0; i < keys; i++) {
      all.add("SYN" + i);
    }
    all.forEach(cache::get);
    int[] perStep = new int[steps];
    for (int s = 0; s < steps; s++) {
      clock.addAndGet(step.toNanos());
      loadsNow[0] = 0;
      all.forEach(cache::get);
      perStep[s] = loadsNow[0];
    }
    return perStep;
  }

  private static int max(int[] values) {
    int max = 0;
    for (int v : values) {
      max = Math.max(max, v);
    }
    return max;
  }

  private static int sum(int[] values) {
    int sum = 0;
    for (int v : values) {
      sum += v;
    }
    return sum;
  }

  private static long usedHeapBytes() {
    for (int i = 0; i < 3; i++) {
      System.gc();
    }
    Runtime runtime = Runtime.getRuntime();
    return runtime.totalMemory() - runtime.freeMemory();
  }
}
