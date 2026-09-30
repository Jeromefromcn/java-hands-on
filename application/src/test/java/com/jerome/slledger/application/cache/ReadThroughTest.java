package com.jerome.slledger.application.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class ReadThroughTest {

  private static final TtlPolicy NEAR =
      new TtlPolicy(Duration.ofSeconds(5), 0, Duration.ofSeconds(2));
  private static final TtlPolicy FAR =
      new TtlPolicy(Duration.ofSeconds(30), 0, Duration.ofSeconds(2));

  private final AtomicLong clock = new AtomicLong();
  private final FakeTier<String, String> l1 = new FakeTier<>(clock);
  private final FakeTier<String, String> l2 = new FakeTier<>(clock);
  private final AtomicInteger loads = new AtomicInteger();

  private ReadThrough<String, String> cache(Function<String, Optional<String>> loader) {
    return new ReadThrough<>(
        List.of(new ReadThrough.Layer<>(l1, NEAR), new ReadThrough.Layer<>(l2, FAR)),
        key -> {
          loads.incrementAndGet();
          return loader.apply(key);
        },
        new Random(1));
  }

  @Test
  void secondReadIsAHit() {
    ReadThrough<String, String> cache = cache(key -> Optional.of("v:" + key));

    assertThat(cache.get("a")).contains("v:a");
    assertThat(cache.get("a")).contains("v:a");

    assertThat(loads).hasValue(1);
    assertThat(cache.counters().hits()).isEqualTo(1);
    assertThat(cache.counters().misses()).isEqualTo(1);
  }

  @Test
  void l2HitIsPromotedToL1() {
    ReadThrough<String, String> cache = cache(key -> Optional.of("loaded"));
    l2.put("a", Entry.present("from-l2"), Duration.ofSeconds(30));

    assertThat(cache.get("a")).contains("from-l2");

    assertThat(l1.holds("a")).isTrue();
    assertThat(loads).hasValue(0);
  }

  @Test
  void unknownIsCachedAsAbsentAndExpires() {
    ReadThrough<String, String> cache = cache(key -> Optional.empty());

    assertThat(cache.get("nope")).isEmpty();
    assertThat(cache.get("nope")).isEmpty();
    assertThat(loads).hasValue(1);

    clock.addAndGet(Duration.ofSeconds(3).toNanos());
    assertThat(cache.get("nope")).isEmpty();
    assertThat(loads).hasValue(2);
    assertThat(l1.ttls()).containsOnly(Duration.ofSeconds(2));
  }

  @Test
  void loadThatThrowsStoresNothing() {
    AtomicInteger calls = new AtomicInteger();
    ReadThrough<String, String> cache =
        cache(
            key -> {
              if (calls.getAndIncrement() == 0) {
                throw new IllegalStateException("source down");
              }
              return Optional.of("ok");
            });

    assertThatThrownBy(() -> cache.get("a")).isInstanceOf(IllegalStateException.class);
    assertThat(l1.holds("a")).isFalse();
    assertThat(cache.get("a")).contains("ok");
    assertThat(cache.counters().loadFailures()).isEqualTo(1);
  }

  @Test
  void throwingTierIsSkippedAndCounted() {
    CacheTier<String, String> broken =
        new CacheTier<>() {
          @Override
          public Optional<Entry<String>> get(String key) {
            throw new IllegalStateException("redis down");
          }

          @Override
          public void put(String key, Entry<String> entry, Duration ttl) {
            throw new IllegalStateException("redis down");
          }

          @Override
          public void evict(String key) {
            throw new IllegalStateException("redis down");
          }
        };
    ReadThrough<String, String> cache =
        new ReadThrough<>(
            List.of(new ReadThrough.Layer<>(l1, NEAR), new ReadThrough.Layer<>(broken, FAR)),
            key -> Optional.of("v"),
            new Random(1));

    assertThat(cache.get("a")).contains("v");
    cache.evict("a");

    assertThat(cache.counters().tierErrors()).isGreaterThanOrEqualTo(3);
  }

  @Test
  @Timeout(30)
  void evictDuringLoadIsNotOverwritten() throws Exception {
    CountDownLatch loading = new CountDownLatch(1);
    CountDownLatch evicted = new CountDownLatch(1);
    ReadThrough<String, String> cache =
        cache(
            key -> {
              loading.countDown();
              await(evicted);
              return Optional.of("old");
            });

    Thread reader = Thread.ofPlatform().start(() -> assertThat(cache.get("a")).contains("old"));
    await(loading);
    cache.evict("a");
    evicted.countDown();
    reader.join(TimeUnit.SECONDS.toMillis(20));

    assertThat(l1.holds("a")).isFalse();
    assertThat(l2.holds("a")).isFalse();
  }

  @Test
  void jitterStaysWithinFractionAndPositive() {
    TtlPolicy policy = new TtlPolicy(Duration.ofSeconds(10), 0.2, Duration.ofSeconds(1));
    Random random = new Random(7);
    for (int i = 0; i < 10_000; i++) {
      assertThat(policy.ttlFor(random)).isBetween(Duration.ofSeconds(8), Duration.ofSeconds(12));
    }
    TtlPolicy extreme = new TtlPolicy(Duration.ofNanos(3), 0.999, Duration.ofSeconds(1));
    for (int i = 0; i < 10_000; i++) {
      assertThat(extreme.ttlFor(random)).isPositive();
    }
    assertThatThrownBy(() -> new TtlPolicy(Duration.ofSeconds(1), 1.0, Duration.ofSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void sameSeedGivesSameTtls() {
    TtlPolicy policy = new TtlPolicy(Duration.ofSeconds(10), 0.2, Duration.ofSeconds(1));
    List<Duration> first = draws(policy, new Random(42));
    List<Duration> second = draws(policy, new Random(42));

    assertThat(first).isEqualTo(second).doesNotHaveDuplicates();
  }

  @Test
  @Timeout(30)
  void singleFlightLoadsOncePerKeyInOneJvm() throws Exception {
    int callers = 50;
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger entered = new AtomicInteger();
    ReadThrough<String, String> cache =
        cache(
            key -> {
              await(release);
              return Optional.of("hot");
            });
    CyclicBarrier start = new CyclicBarrier(callers);
    List<Thread> threads = new ArrayList<>();
    for (int i = 0; i < callers; i++) {
      threads.add(
          Thread.ofPlatform()
              .start(
                  () -> {
                    try {
                      start.await();
                      entered.incrementAndGet();
                      assertThat(cache.get("hot")).contains("hot");
                    } catch (Exception e) {
                      throw new IllegalStateException(e);
                    }
                  }));
    }
    try {
      awaitAllParked(threads, entered, callers);
    } finally {
      release.countDown();
    }
    for (Thread thread : threads) {
      thread.join(TimeUnit.SECONDS.toMillis(20));
    }

    assertThat(loads).hasValue(1);
  }

  @Test
  @Timeout(30)
  void reproducesStampedeWithoutSingleFlight() throws Exception {
    // See docs/decisions/09-caching.md
    int callers = 50;
    CountDownLatch allInLoader = new CountDownLatch(callers);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger sourceCalls = new AtomicInteger();
    CyclicBarrier start = new CyclicBarrier(callers);
    List<Thread> threads = new ArrayList<>();
    for (int i = 0; i < callers; i++) {
      threads.add(
          Thread.ofPlatform()
              .start(
                  () -> {
                    try {
                      start.await();
                      // A naive cache-aside reader: check, load, store. Nothing joins the crowd.
                      if (l1.get("hot").isEmpty()) {
                        sourceCalls.incrementAndGet();
                        allInLoader.countDown();
                        await(release);
                        l1.put("hot", Entry.present("hot"), Duration.ofSeconds(5));
                      }
                    } catch (Exception e) {
                      throw new IllegalStateException(e);
                    }
                  }));
    }
    try {
      assertThat(allInLoader.await(20, TimeUnit.SECONDS)).isTrue();
    } finally {
      release.countDown();
    }
    for (Thread thread : threads) {
      thread.join(TimeUnit.SECONDS.toMillis(20));
    }

    assertThat(sourceCalls).hasValue(callers);
  }

  @Test
  @Timeout(30)
  void waitersOfAFailedLoadEachLoadForThemselves() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    AtomicInteger entered = new AtomicInteger();
    ReadThrough<String, String> cache =
        cache(
            key -> {
              if (calls.getAndIncrement() == 0) {
                await(release);
                throw new IllegalStateException("first load fails");
              }
              return Optional.of("ok");
            });
    List<String> results = java.util.Collections.synchronizedList(new ArrayList<>());
    List<Thread> threads = new ArrayList<>();
    Thread leader =
        Thread.ofPlatform()
            .start(
                () -> {
                  entered.incrementAndGet();
                  try {
                    cache.get("a");
                  } catch (IllegalStateException expected) {
                    results.add("leader-failed");
                  }
                });
    threads.add(leader);
    awaitAllParked(threads, entered, 1);
    for (int i = 0; i < 3; i++) {
      threads.add(
          Thread.ofPlatform()
              .start(
                  () -> {
                    entered.incrementAndGet();
                    results.add(cache.get("a").orElse("empty"));
                  }));
    }
    try {
      awaitAllParked(threads, entered, 4);
    } finally {
      release.countDown();
    }
    for (Thread thread : threads) {
      thread.join(TimeUnit.SECONDS.toMillis(20));
    }

    assertThat(results).containsExactlyInAnyOrder("leader-failed", "ok", "ok", "ok");
  }

  private static List<Duration> draws(TtlPolicy policy, Random random) {
    List<Duration> draws = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      draws.add(policy.ttlFor(random));
    }
    return draws;
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(25, TimeUnit.SECONDS)) {
        throw new IllegalStateException("latch never opened");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** Every thread has entered the cache and is parked, so nobody is about to load or return. */
  static void awaitAllParked(List<Thread> threads, AtomicInteger entered, int expected) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (System.nanoTime() < deadline) {
      if (entered.get() == expected
          && threads.stream()
              .allMatch(
                  t ->
                      t.getState() == Thread.State.WAITING
                          || t.getState() == Thread.State.TIMED_WAITING)) {
        return;
      }
      Thread.onSpinWait();
    }
    throw new IllegalStateException("threads did not all park");
  }

  /** An in-process stand-in for a lock two "instances" share. */
  private static final class SharedLock implements DistributedLock {
    private final java.util.Set<String> held = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Override
    public Optional<Lease> tryAcquire(String resource, Duration ttl) {
      if (!held.add(resource)) {
        return Optional.empty();
      }
      return Optional.of(
          new Lease() {
            @Override
            public String resource() {
              return resource;
            }

            @Override
            public long fencingToken() {
              return 1;
            }

            @Override
            public void release() {
              held.remove(resource);
            }
          });
    }
  }

  @Test
  @Timeout(30)
  void theRebuildLockIsHeldUntilTheValueIsStored() throws Exception {
    // The instance that loaded is still writing the value to the shared tier when a second instance
    // asks. If the lock had already been released, the second would take it, find the tier empty
    // and load again.
    FakeTier<String, String> shared = new FakeTier<>(clock);
    CountDownLatch storing = new CountDownLatch(1);
    CountDownLatch finishStoring = new CountDownLatch(1);
    CacheTier<String, String> slowShared =
        new CacheTier<>() {
          @Override
          public Optional<Entry<String>> get(String key) {
            return shared.get(key);
          }

          @Override
          public void put(String key, Entry<String> entry, Duration ttl) {
            storing.countDown();
            await(finishStoring);
            shared.put(key, entry, ttl);
          }

          @Override
          public void evict(String key) {
            shared.evict(key);
          }
        };
    SharedLock lock = new SharedLock();
    AtomicInteger sourceCalls = new AtomicInteger();
    AtomicInteger pauses = new AtomicInteger();
    Function<String, Optional<String>> source =
        key -> {
          sourceCalls.incrementAndGet();
          return Optional.of("hot");
        };
    ReadThrough<String, String> first =
        new ReadThrough<>(
            List.of(
                new ReadThrough.Layer<>(new FakeTier<String, String>(clock), NEAR),
                new ReadThrough.Layer<>(slowShared, FAR)),
            source,
            new Random(1),
            new ReadThrough.Rebuild<>(
                lock, key -> "r:" + key, Duration.ofSeconds(10), 100, () -> {}));
    ReadThrough<String, String> second =
        new ReadThrough<>(
            List.of(
                new ReadThrough.Layer<>(new FakeTier<String, String>(clock), NEAR),
                new ReadThrough.Layer<>(shared, FAR)),
            source,
            new Random(2),
            new ReadThrough.Rebuild<>(
                lock,
                key -> "r:" + key,
                Duration.ofSeconds(10),
                100,
                () -> {
                  pauses.incrementAndGet();
                  finishStoring
                      .countDown(); // the first instance's write completes while this one waits
                }));

    Thread loader = Thread.ofPlatform().start(() -> first.get("hot"));
    try {
      assertThat(storing.await(20, TimeUnit.SECONDS)).isTrue();
      Optional<String> answer = second.get("hot");

      assertThat(answer).contains("hot");
      assertThat(sourceCalls).hasValue(1);
    } finally {
      finishStoring.countDown();
      loader.join(TimeUnit.SECONDS.toMillis(20));
    }
  }
}
