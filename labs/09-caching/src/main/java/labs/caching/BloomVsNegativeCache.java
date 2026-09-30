package labs.caching;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Two fixes for a request that names something the store does not hold: remember the miss (the
 * main build's choice), or put a Bloom filter of the keys that do exist in front of the store.
 * Counted in store lookups, on two workloads, plus the one thing each gets wrong.
 */
public final class BloomVsNegativeCache {

  private static final int KNOWN = 10_000;
  private static final double FALSE_POSITIVE_RATE = 0.01;
  private static final int NEGATIVE_CACHE_SIZE = 1_000;

  private BloomVsNegativeCache() {}

  public static void main(String[] args) throws Exception {
    Set<String> store = new HashSet<>();
    for (int i = 0; i < KNOWN; i++) {
      store.add("SYN%07d".formatted(i));
    }

    // A: a scan. 100,000 different unknown keys, each asked once.
    String[] scan = new String[100_000];
    for (int i = 0; i < scan.length; i++) {
      scan[i] = "BAD%07d".formatted(i);
    }
    // B: a hot bad set. 100 unknown keys, 1,000 requests each.
    String[] hot = new String[100_000];
    for (int i = 0; i < hot.length; i++) {
      hot[i] = "BAD%07d".formatted(i % 100);
    }

    StringBuilder out = new StringBuilder();
    out.append("Stage 9 lab: Bloom filter against a negative cache\n");
    out.append("Environment: ").append(Env.describe()).append("\n");
    out.append(
        """
        Store: %d known keys. Bloom filter: Guava, false-positive rate %.2f. Negative cache:
        Caffeine, maximumSize %d, no TTL (the count is of lookups, not of time).
        Store lookups = requests that reached the store.

        """
            .formatted(KNOWN, FALSE_POSITIVE_RATE, NEGATIVE_CACHE_SIZE));

    out.append("workload                          none      negative cache     Bloom filter\n");
    out.append(row("A: scan, 100,000 distinct", scan, store));
    out.append(row("B: 100 keys x 1,000 requests", hot, store));

    // Memory, and what the filter cannot learn.
    long beforeBloom = usedHeap();
    BloomFilter<String> bloom = bloom(store);
    long bloomBytes = usedHeap() - beforeBloom;
    double bits = -KNOWN * Math.log(FALSE_POSITIVE_RATE) / (Math.log(2) * Math.log(2));
    long falsePositiveKeysInB = java.util.Arrays.stream(hot).distinct().filter(bloom::mightContain).count();
    out.append(
        "\nBloom filter heap: %d bytes measured (theory: %.0f bits = %.0f bytes for %d keys).%n"
            .formatted(bloomBytes, bits, bits / 8, KNOWN));
    out.append(
        "Negative cache heap: bounded by maximumSize (%d entries), not measured here.%n"
            .formatted(NEGATIVE_CACHE_SIZE));
    out.append(
        "In workload B the filter's lookups are %d false-positive key(s) asked over and over:%n"
            .formatted(falsePositiveKeysInB));
    out.append("the filter never learns that a key it passed was not in the store.\n");

    // What each gets wrong.
    String newlyListed = "SYN9999999";
    store.add(newlyListed);
    boolean bloomAdmits = bloom.mightContain(newlyListed);
    Cache<String, Boolean> stale = Caffeine.newBuilder().build();
    stale.put(newlyListed, Boolean.FALSE); // asked about just before it was listed
    boolean negativeServed = stale.getIfPresent(newlyListed) != null;
    out.append(
        """

        A security listed after the filter was built (%s):
          Bloom filter (built before it): mightContain = %b. The key was not in the set the
            filter was built from, so a real security is refused until the filter is rebuilt.
            Nothing expires that refusal on its own.
          negative cache (entry made just before it was listed): served from cache = %b. It is
            refused until that entry's TTL runs out, which is bounded and needs no rebuild.
        """
            .formatted(newlyListed, bloomAdmits, negativeServed));

    Path target = Path.of("target", "evidence");
    Files.createDirectories(target);
    Files.writeString(target.resolve("09-bloom-vs-negative.txt"), out.toString());
    System.out.println(out);
  }

  private static String row(String name, String[] requests, Set<String> store) {
    int none = 0;
    for (String key : requests) {
      if (!store.contains(key)) {
        none++;
      }
    }

    AtomicInteger negativeLookups = new AtomicInteger();
    Cache<String, Boolean> negative = Caffeine.newBuilder().maximumSize(NEGATIVE_CACHE_SIZE).build();
    for (String key : requests) {
      negative.get(
          key,
          k -> {
            negativeLookups.incrementAndGet();
            return store.contains(k);
          });
    }

    BloomFilter<String> bloom = bloom(store);
    int bloomLookups = 0;
    for (String key : requests) {
      if (bloom.mightContain(key)) {
        bloomLookups++;
      }
    }
    return "%-30s %7d %17d %16d%n".formatted(name, none, negativeLookups.get(), bloomLookups);
  }

  private static BloomFilter<String> bloom(Set<String> store) {
    BloomFilter<String> filter =
        BloomFilter.create(Funnels.stringFunnel(StandardCharsets.UTF_8), KNOWN, FALSE_POSITIVE_RATE);
    store.stream().limit(KNOWN).forEach(filter::put);
    return filter;
  }

  private static long usedHeap() {
    for (int i = 0; i < 3; i++) {
      System.gc();
    }
    Runtime runtime = Runtime.getRuntime();
    return runtime.totalMemory() - runtime.freeMemory();
  }
}
