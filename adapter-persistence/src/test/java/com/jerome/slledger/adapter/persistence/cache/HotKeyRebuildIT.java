package com.jerome.slledger.adapter.persistence.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.persistence.testing.Evidence;
import com.jerome.slledger.adapter.persistence.testing.SharedRedis;
import com.jerome.slledger.application.cache.CacheTier;
import com.jerome.slledger.application.cache.Entry;
import com.jerome.slledger.application.cache.ReadThrough;
import com.jerome.slledger.application.cache.TtlPolicy;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.money.Money;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * One hot key, expired everywhere at once. Two "instances" are two {@link ReadThrough}s, each with
 * a Caffeine tier of its own, sharing one real Redis tier and one real Redis lock.
 */
@Timeout(60)
class HotKeyRebuildIT {

  private static final Isin HOT = new Isin("HK0000000001");
  private static final Money PRICE = Money.of("41.26", Currency.getInstance("HKD"));
  private static final TtlPolicy L1 =
      new TtlPolicy(Duration.ofSeconds(5), 0, Duration.ofSeconds(2));
  private static final TtlPolicy L2 =
      new TtlPolicy(Duration.ofSeconds(30), 0, Duration.ofSeconds(2));

  private static final StringBuilder EVIDENCE =
      new StringBuilder(
          """
          Stage 9, section 9: source calls for one hot key requested by many callers at once.
          The loader parks until every caller is in the cache or in the loader, so a stampede
          cannot be hidden by one caller finishing early. Real Redis (tier and lock).

          """);

  private SharedRedis.Client redis;
  private RedisLock lock;
  private RedisPriceTier shared;
  private final AtomicInteger sourceCalls = new AtomicInteger();

  @BeforeAll
  static void start() {
    SharedRedis.start();
  }

  @org.junit.jupiter.api.AfterAll
  static void writeEvidence() {
    Evidence.write("09-breakdown.txt", EVIDENCE.toString());
  }

  @BeforeEach
  void connect() {
    redis = SharedRedis.client();
    lock =
        new RedisLock(
            redis.template(),
            "lock:",
            new java.util.concurrent.atomic.AtomicLong()::incrementAndGet);
    shared = new RedisPriceTier(redis.template(), "price:", new PriceCodec());
  }

  @AfterEach
  void clean() {
    SharedRedis.flushAll();
    redis.close();
  }

  private ReadThrough<Isin, Money> instance(
      java.util.function.Function<Isin, Optional<Money>> loader,
      ReadThrough.Rebuild<Isin> rebuild) {
    CacheTier<Isin, Money> l1 = new CaffeineTier<>(1_000);
    return new ReadThrough<>(
        List.of(new ReadThrough.Layer<>(l1, L1), new ReadThrough.Layer<>(shared, L2)),
        loader,
        new Random(1),
        rebuild);
  }

  private ReadThrough.Rebuild<Isin> rebuild(int polls, Runnable pause) {
    return new ReadThrough.Rebuild<>(
        lock, isin -> "price:" + isin.value(), Duration.ofSeconds(30), polls, pause);
  }

  @Test
  void twoInstancesLoadOnce() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger pauses = new AtomicInteger();
    java.util.function.Function<Isin, Optional<Money>> loader =
        isin -> {
          sourceCalls.incrementAndGet();
          await(release);
          return Optional.of(PRICE);
        };
    Runnable pause =
        () -> {
          pauses.incrementAndGet();
          Thread.yield();
        };
    ReadThrough<Isin, Money> a = instance(loader, rebuild(Integer.MAX_VALUE, pause));
    ReadThrough<Isin, Money> b = instance(loader, rebuild(Integer.MAX_VALUE, pause));
    List<Optional<Money>> answers = java.util.Collections.synchronizedList(new ArrayList<>());

    Thread first = Thread.ofPlatform().start(() -> answers.add(a.get(HOT)));
    awaitCount(sourceCalls, 1);
    Thread second = Thread.ofPlatform().start(() -> answers.add(b.get(HOT)));
    try {
      awaitAtLeast(pauses, 1);
    } finally {
      release.countDown();
    }
    first.join(TimeUnit.SECONDS.toMillis(20));
    second.join(TimeUnit.SECONDS.toMillis(20));

    assertThat(answers).containsExactly(Optional.of(PRICE), Optional.of(PRICE));
    assertThat(sourceCalls).hasValue(1);
    assertThat(shared.get(HOT)).contains(Entry.present(PRICE));
    EVIDENCE.append("2 instances, shared lock : %d source call%n".formatted(sourceCalls.get()));
  }

  @Test
  void lockHolderFailureDoesNotBlockTheOthers() throws Exception {
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger pauses = new AtomicInteger();
    AtomicInteger calls = new AtomicInteger();
    java.util.function.Function<Isin, Optional<Money>> loader =
        isin -> {
          sourceCalls.incrementAndGet();
          if (calls.getAndIncrement() == 0) {
            await(release);
            throw new IllegalStateException("the first load fails");
          }
          return Optional.of(PRICE);
        };
    Runnable pause =
        () -> {
          pauses.incrementAndGet();
          Thread.yield();
        };
    ReadThrough<Isin, Money> a = instance(loader, rebuild(Integer.MAX_VALUE, pause));
    ReadThrough<Isin, Money> b = instance(loader, rebuild(Integer.MAX_VALUE, pause));
    AtomicInteger failures = new AtomicInteger();
    List<Optional<Money>> answers = java.util.Collections.synchronizedList(new ArrayList<>());

    Thread first =
        Thread.ofPlatform()
            .start(
                () -> {
                  try {
                    a.get(HOT);
                  } catch (IllegalStateException expected) {
                    failures.incrementAndGet();
                  }
                });
    awaitCount(sourceCalls, 1);
    Thread second = Thread.ofPlatform().start(() -> answers.add(b.get(HOT)));
    try {
      awaitAtLeast(pauses, 1);
    } finally {
      release.countDown();
    }
    first.join(TimeUnit.SECONDS.toMillis(20));
    second.join(TimeUnit.SECONDS.toMillis(20));

    assertThat(failures).hasValue(1);
    assertThat(answers).containsExactly(Optional.of(PRICE));
    assertThat(sourceCalls).hasValue(2);
  }

  @Test
  void waiterFallsBackAfterBoundedPolls() {
    // Someone else holds the lock and never releases it.
    lock.tryAcquire("price:" + HOT.value(), Duration.ofSeconds(30)).orElseThrow();
    AtomicInteger pauses = new AtomicInteger();
    ReadThrough<Isin, Money> b =
        instance(
            isin -> {
              sourceCalls.incrementAndGet();
              return Optional.of(PRICE);
            },
            rebuild(3, pauses::incrementAndGet));

    assertThat(b.get(HOT)).contains(PRICE);

    assertThat(pauses).hasValue(3);
    assertThat(sourceCalls).hasValue(1);
    assertThat(b.counters().lockFallbacks()).isEqualTo(1);
  }

  @Test
  void reproducesStampedeAcrossInstancesWithoutLock() throws Exception {
    // See docs/decisions/09-caching.md
    CountDownLatch bothInLoader = new CountDownLatch(2);
    java.util.function.Function<Isin, Optional<Money>> loader =
        isin -> {
          sourceCalls.incrementAndGet();
          bothInLoader.countDown();
          await(bothInLoader);
          return Optional.of(PRICE);
        };
    ReadThrough<Isin, Money> a = instance(loader, null);
    ReadThrough<Isin, Money> b = instance(loader, null);

    Thread first = Thread.ofPlatform().start(() -> a.get(HOT));
    Thread second = Thread.ofPlatform().start(() -> b.get(HOT));
    first.join(TimeUnit.SECONDS.toMillis(20));
    second.join(TimeUnit.SECONDS.toMillis(20));

    assertThat(sourceCalls).hasValue(2);
    EVIDENCE.append("2 instances, no lock     : %d source calls%n".formatted(sourceCalls.get()));
  }

  @Test
  void oneJvmWithAndWithoutSingleFlight() throws Exception {
    int callers = 50;
    int withFlight = oneJvm(callers, true);
    int withoutFlight = oneJvm(callers, false);

    assertThat(withFlight).isEqualTo(1);
    assertThat(withoutFlight).isEqualTo(callers);
    EVIDENCE.append(
        "1 JVM, %d threads, single-flight : %d source call%n".formatted(callers, withFlight));
    EVIDENCE.append(
        "1 JVM, %d threads, naive reader  : %d source calls%n".formatted(callers, withoutFlight));
  }

  private int oneJvm(int callers, boolean singleFlight) throws Exception {
    AtomicInteger calls = new AtomicInteger();
    AtomicInteger entered = new AtomicInteger();
    CountDownLatch release = new CountDownLatch(1);
    CountDownLatch allInLoader = new CountDownLatch(callers);
    CaffeineTier<Isin, Money> tier = new CaffeineTier<>(1_000);
    ReadThrough<Isin, Money> flight =
        new ReadThrough<>(
            List.of(new ReadThrough.Layer<>(tier, L1)),
            isin -> {
              calls.incrementAndGet();
              await(release);
              return Optional.of(PRICE);
            },
            new Random(1));
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
                      if (singleFlight) {
                        flight.get(HOT);
                      } else if (tier.get(HOT).isEmpty()) {
                        calls.incrementAndGet();
                        allInLoader.countDown();
                        await(release);
                        tier.put(HOT, Entry.present(PRICE), Duration.ofSeconds(5));
                      }
                    } catch (Exception e) {
                      throw new IllegalStateException(e);
                    }
                  }));
    }
    try {
      if (singleFlight) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline
            && !(entered.get() == callers
                && threads.stream()
                    .allMatch(
                        t ->
                            t.getState() == Thread.State.WAITING
                                || t.getState() == Thread.State.TIMED_WAITING))) {
          Thread.onSpinWait();
        }
      } else {
        assertThat(allInLoader.await(20, TimeUnit.SECONDS)).isTrue();
      }
    } finally {
      release.countDown();
    }
    for (Thread thread : threads) {
      thread.join(TimeUnit.SECONDS.toMillis(20));
    }
    return calls.get();
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

  private static void awaitCount(AtomicInteger counter, int expected) {
    awaitAtLeast(counter, expected);
  }

  private static void awaitAtLeast(AtomicInteger counter, int minimum) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (counter.get() < minimum) {
      if (System.nanoTime() > deadline) {
        throw new IllegalStateException("counter stuck at " + counter.get());
      }
      Thread.onSpinWait();
    }
  }
}
