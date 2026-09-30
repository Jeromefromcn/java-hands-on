package com.jerome.slledger.adapter.persistence.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.persistence.testing.Evidence;
import com.jerome.slledger.adapter.persistence.testing.SharedRedis;
import com.jerome.slledger.application.cache.Entry;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.money.Money;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The order in which a writer touches the store and the cache, against a real Redis tier. A
 * test-side store stands in for the database, because this ledger has no price writer: prices come
 * from outside. Each interleaving is forced with latches, and the events are recorded in the order
 * they happened, which is the timeline the decision record draws.
 */
@Timeout(60)
class WriteOrderIT {

  private static final Currency HKD = Currency.getInstance("HKD");
  private static final Isin ISIN = new Isin("HK0000000001");
  private static final Money OLD = Money.of("41.26", HKD);
  private static final Money NEW = Money.of("42.00", HKD);
  private static final Duration TTL = Duration.ofSeconds(30);

  private static final StringBuilder EVIDENCE =
      new StringBuilder(
          """
          Stage 9, section 11: cache-aside write orders, real Redis tier, a test-side store.
          R is a reader that misses and loads from the store; W is a writer changing the price
          from 41.26 to 42.00. Events are numbered in the order they happened. "Stale for" is the
          PTTL Redis reports for the stale entry when the scenario ends: the value stays until
          that TTL runs out or something evicts it.

          """);

  private SharedRedis.Client redis;
  private RedisPriceTier cache;
  private final AtomicReference<Money> store = new AtomicReference<>(OLD);
  private final List<String> events = Collections.synchronizedList(new ArrayList<>());
  private final AtomicInteger sequence = new AtomicInteger();

  @BeforeAll
  static void start() {
    SharedRedis.start();
  }

  @AfterAll
  static void writeEvidence() {
    Evidence.write("09-write-order.txt", EVIDENCE.toString());
  }

  @BeforeEach
  void connect() {
    redis = SharedRedis.client();
    cache = new RedisPriceTier(redis.template(), "price:", new PriceCodec());
  }

  @AfterEach
  void clean() {
    SharedRedis.flushAll();
    redis.close();
  }

  private void event(String what) {
    events.add("%d. %s".formatted(sequence.incrementAndGet(), what));
  }

  private Optional<Money> cached() {
    return cache.get(ISIN).map(e -> ((Entry.Present<Money>) e).value());
  }

  /** R's read half: a miss, then the store. Returns what R loaded, before it has cached it. */
  private Money readerLoadsFromStore() {
    assertThat(cached()).isEmpty();
    event("R: cache miss");
    Money loaded = store.get();
    event("R: loaded %s from the store".formatted(loaded.amount().toPlainString()));
    return loaded;
  }

  private void readerCaches(Money loaded) {
    cache.put(ISIN, Entry.present(loaded), TTL);
    event("R: cached %s".formatted(loaded.amount().toPlainString()));
  }

  private void writerUpdatesStore() {
    store.set(NEW);
    event("W: updated the store to 42.00");
  }

  private void writerDeletesCache(String which) {
    cache.evict(ISIN);
    event("W: %s delete of the cache entry".formatted(which));
  }

  private interface Scenario {
    void run(
        CountDownLatch readerMayStart,
        CountDownLatch readerHasLoaded,
        CountDownLatch readerMayCache)
        throws Exception;
  }

  /** Runs R on a thread that pauses between its load and its cache write. */
  private void withPausedReader(Scenario writerSide) throws Exception {
    CountDownLatch readerMayStart = new CountDownLatch(1);
    CountDownLatch readerHasLoaded = new CountDownLatch(1);
    CountDownLatch readerMayCache = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread reader =
        Thread.ofPlatform()
            .start(
                () -> {
                  try {
                    await(readerMayStart);
                    Money loaded = readerLoadsFromStore();
                    readerHasLoaded.countDown();
                    await(readerMayCache);
                    readerCaches(loaded);
                  } catch (Throwable t) {
                    failure.set(t);
                  }
                });
    try {
      writerSide.run(readerMayStart, readerHasLoaded, readerMayCache);
    } finally {
      readerMayStart.countDown();
      readerMayCache.countDown();
      reader.join(TimeUnit.SECONDS.toMillis(20));
    }
    assertThat(failure.get()).isNull();
  }

  private void record(String title, String outcome) {
    Long ttl = redis.template().getExpire("price:" + ISIN.value(), TimeUnit.MILLISECONDS);
    EVIDENCE.append(title).append('\n');
    events.forEach(e -> EVIDENCE.append("  ").append(e).append('\n'));
    EVIDENCE
        .append("  => ")
        .append(outcome)
        .append(ttl != null && ttl > 0 ? " (stale for up to %d ms)".formatted(ttl) : "")
        .append("\n\n");
  }

  @Test
  void reproducesStaleCacheAfterUpdateThenDelete() throws Exception {
    // See docs/decisions/09-caching.md
    // Order A: update the store, then delete the cache. R read the old value before the update
    // and writes it back after the delete.
    withPausedReader(
        (start, loaded, mayCache) -> {
          start.countDown();
          await(loaded);
          writerUpdatesStore();
          writerDeletesCache("first");
          mayCache.countDown();
        });
    awaitSequence(4);

    assertThat(cached()).contains(OLD);
    assertThat(store.get()).isEqualTo(NEW);
    record(
        "Order A: update the store, then delete the cache", "cache holds 41.26, store holds 42.00");
  }

  @Test
  void reproducesStaleCacheAfterDeleteThenUpdate() throws Exception {
    // See docs/decisions/09-caching.md
    // Order B: delete the cache, then update the store. R slips in between the two.
    withPausedReader(
        (start, loaded, mayCache) -> {
          writerDeletesCache("first");
          start.countDown();
          mayCache.countDown();
          await(loaded);
          // R loaded the old value (the store is not updated yet) and caches it.
          awaitCached();
          writerUpdatesStore();
        });

    assertThat(cached()).contains(OLD);
    assertThat(store.get()).isEqualTo(NEW);
    record(
        "Order B: delete the cache, then update the store", "cache holds 41.26, store holds 42.00");
  }

  @Test
  void delayedSecondDeleteRepairsBothOrders() throws Exception {
    // Order A, then the second delete after the delay.
    withPausedReader(
        (start, loaded, mayCache) -> {
          start.countDown();
          await(loaded);
          writerUpdatesStore();
          writerDeletesCache("first");
          mayCache.countDown();
          awaitCached();
          writerDeletesCache("second (after the delay)");
        });
    assertThat(cached()).isEmpty();
    assertThat(cache.get(ISIN)).isEmpty();
    record("Order A + delayed second delete", "cache empty; the next read loads 42.00");
    events.clear();
    sequence.set(0);
    store.set(OLD);
    redis.template().delete("price:" + ISIN.value());

    // Order B, then the second delete after the delay.
    withPausedReader(
        (start, loaded, mayCache) -> {
          writerDeletesCache("first");
          start.countDown();
          mayCache.countDown();
          await(loaded);
          awaitCached();
          writerUpdatesStore();
          writerDeletesCache("second (after the delay)");
        });
    assertThat(cached()).isEmpty();
    record("Order B + delayed second delete", "cache empty; the next read loads 42.00");
  }

  @Test
  void reproducesDelayedDeleteDefeatedBySlowReader() throws Exception {
    // See docs/decisions/09-caching.md
    // R loaded the old value and is slower than the writer's delay: the second delete has already
    // run when R finally writes.
    withPausedReader(
        (start, loaded, mayCache) -> {
          start.countDown();
          await(loaded);
          writerUpdatesStore();
          writerDeletesCache("first");
          writerDeletesCache("second (after the delay)");
          mayCache.countDown();
          awaitCached();
        });

    assertThat(cached()).contains(OLD);
    assertThat(store.get()).isEqualTo(NEW);
    record(
        "Order A + delayed second delete, reader slower than the delay",
        "cache holds 41.26, store holds 42.00: the second delete ran before R cached");
  }

  private void awaitCached() {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (cached().isEmpty()) {
      if (System.nanoTime() > deadline) {
        throw new IllegalStateException("R never cached");
      }
      Thread.onSpinWait();
    }
  }

  private void awaitSequence(int events) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (sequence.get() < events) {
      if (System.nanoTime() > deadline) {
        throw new IllegalStateException("events stuck at " + sequence.get());
      }
      Thread.onSpinWait();
    }
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
}
