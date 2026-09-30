package com.jerome.slledger.adapter.persistence.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.adapter.persistence.testing.Evidence;
import com.jerome.slledger.adapter.persistence.testing.SharedRedis;
import com.jerome.slledger.application.cache.CachingMarketPriceSource;
import com.jerome.slledger.application.cache.Entry;
import com.jerome.slledger.application.cache.ReadThrough;
import com.jerome.slledger.application.cache.TtlPolicy;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.money.Money;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Currency;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Two instances, each with its own Caffeine tier, one shared Redis tier, and the invalidation
 * channel between them. The source's price changes from {@code 41.26} to {@code 42.00}.
 */
@Timeout(60)
class PriceInvalidationIT {

  private static final Currency HKD = Currency.getInstance("HKD");
  private static final Isin ISIN = new Isin("HK0000000001");
  private static final Money OLD = Money.of("41.26", HKD);
  private static final Money NEW = Money.of("42.00", HKD);
  private static final Duration L1_TTL = Duration.ofSeconds(5);
  private static final TtlPolicy L1 = new TtlPolicy(L1_TTL, 0, Duration.ofSeconds(2));
  private static final TtlPolicy L2 =
      new TtlPolicy(Duration.ofSeconds(30), 0, Duration.ofSeconds(2));
  private static final String CHANNEL = "sl:price:invalidate";

  private final AtomicReference<Money> sourcePrice = new AtomicReference<>(OLD);
  private SharedRedis.Client redis;
  private final List<Node> nodes = new ArrayList<>();

  @BeforeAll
  static void start() {
    SharedRedis.start();
  }

  @BeforeEach
  void connect() {
    redis = SharedRedis.client();
  }

  @AfterEach
  void clean() {
    nodes.forEach(Node::close);
    SharedRedis.flushAll();
    redis.close();
  }

  /** One instance: its own ticker, its own L1, the shared L2, and optionally the channel. */
  private final class Node implements AutoCloseable {

    final AtomicLong ticker = new AtomicLong();
    final CaffeineTier<Isin, Money> l1 = new CaffeineTier<>(1_000, ticker::get);
    final SharedRedis.Client client = SharedRedis.client();
    final RedisInvalidationBus bus;
    final CachingMarketPriceSource prices;

    Node(boolean withChannel) {
      bus =
          withChannel
              ? new RedisInvalidationBus(client.factory(), client.template(), CHANNEL)
              : null;
      prices =
          new CachingMarketPriceSource(
              isin -> sourcePrice.get(),
              List.of(
                  new ReadThrough.Layer<>(l1, L1),
                  new ReadThrough.Layer<>(
                      new RedisPriceTier(client.template(), "price:", new PriceCodec()), L2)),
              new Random(1),
              null,
              bus);
      if (bus != null) {
        bus.start(prices::evictLocal);
      }
      nodes.add(this);
    }

    void advance(Duration by) {
      ticker.addAndGet(by.toNanos());
    }

    @Override
    public void close() {
      if (bus != null) {
        bus.close();
      }
      client.close();
    }
  }

  @Test
  void evictOnOneClearsTheOthersL1() {
    Node a = new Node(true);
    Node b = new Node(true);
    assertThat(a.prices.priceOf(ISIN)).isEqualTo(OLD);
    assertThat(b.prices.priceOf(ISIN)).isEqualTo(OLD);
    sourcePrice.set(NEW);

    a.prices.evict(ISIN);
    awaitEmpty(b);

    assertThat(b.prices.priceOf(ISIN)).isEqualTo(NEW);
    assertThat(a.prices.priceOf(ISIN)).isEqualTo(NEW);
  }

  @Test
  void invalidationLatency() {
    Node a = new Node(true);
    Node b = new Node(true);
    int rounds = 200;
    List<Long> micros = new ArrayList<>();
    for (int i = 0; i < rounds; i++) {
      b.l1.put(ISIN, Entry.present(OLD), L1_TTL);
      long start = System.nanoTime();
      a.prices.evict(ISIN);
      awaitEmpty(b);
      micros.add(TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - start));
    }
    Collections.sort(micros);

    assertThat(micros).hasSize(rounds);
    Evidence.write(
        "09-invalidation-latency.txt",
        """
        Stage 9, section 10: time from A.evict(isin) to B's L1 being empty, %d rounds, two
        instances and one real redis:7.4-alpine on this machine (loopback, Testcontainers).
        Includes A's own L1 and shared-tier delete and the PUBLISH; measured with System.nanoTime.

        median = %d us, p99 = %d us, max = %d us
        """
            .formatted(
                rounds,
                micros.get(rounds / 2),
                micros.get((int) Math.ceil(rounds * 0.99) - 1),
                micros.get(rounds - 1)));
  }

  @Test
  void reproducesStaleL1WithoutInvalidation() {
    // See docs/decisions/09-caching.md
    Node a = new Node(false);
    Node b = new Node(false);
    a.prices.priceOf(ISIN);
    b.prices.priceOf(ISIN);
    sourcePrice.set(NEW);

    a.prices.evict(ISIN);

    // A and the shared tier are clean. B's own copy is not, and nothing tells it.
    assertThat(a.prices.priceOf(ISIN)).isEqualTo(NEW);
    assertThat(b.prices.priceOf(ISIN)).isEqualTo(OLD);
    b.advance(L1_TTL.minusNanos(1));
    assertThat(b.prices.priceOf(ISIN)).isEqualTo(OLD);
    b.advance(Duration.ofNanos(1));
    assertThat(b.prices.priceOf(ISIN)).isEqualTo(NEW);

    Evidence.write("09-invalidation-stale.txt", staleEvidence("no channel", L1_TTL, L1_TTL));
  }

  @Test
  void reproducesMissedInvalidationWhenSubscriberWasDown() {
    // See docs/decisions/09-caching.md
    Node a = new Node(true);
    Node b = new Node(true);
    a.prices.priceOf(ISIN);
    b.prices.priceOf(ISIN);
    sourcePrice.set(NEW);
    b.bus.stop();

    a.prices.evict(ISIN);
    b.bus.start(b.prices::evictLocal);

    // The channel is back, but pub/sub does not replay: the message is gone.
    assertThat(b.prices.priceOf(ISIN)).isEqualTo(OLD);
    b.advance(L1_TTL.minusNanos(1));
    assertThat(b.prices.priceOf(ISIN)).isEqualTo(OLD);
    b.advance(Duration.ofNanos(1));
    assertThat(b.prices.priceOf(ISIN)).isEqualTo(NEW);
  }

  @Test
  void aMessageThatIsNotAnIsinIsRefusedAndHarmless() {
    Node a = new Node(true);
    a.prices.priceOf(ISIN);

    redis.template().convertAndSend(CHANNEL, "not an isin");
    redis.template().convertAndSend(CHANNEL, ISIN.value());
    awaitEmpty(a);
    // The listener container hands messages to a thread pool, so the ISIN's eviction can be seen
    // before the refusal of the message sent ahead of it has been counted.
    awaitRefused(a, 1);

    assertThat(a.bus.refused()).isEqualTo(1);
  }

  private static String staleEvidence(String mode, Duration served, Duration bound) {
    return """
        Stage 9, section 10: how long instance B kept serving the old price (41.26) after the
        price changed to 42.00 and instance A evicted it. L1 TTL = %s, jitter 0, controlled
        ticker, so the window is exact by construction: it is the L1 TTL, not the channel.

        %s: B served the old price until the L1 TTL ran out (%s)
        B at TTL-1ns: old price. B at TTL: new price.
        """
        .formatted(served, mode, bound);
  }

  private static void awaitRefused(Node node, long expected) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (node.bus.refused() < expected) {
      if (System.nanoTime() > deadline) {
        throw new IllegalStateException("the refusal was never counted");
      }
      Thread.onSpinWait();
    }
  }

  private static void awaitEmpty(Node node) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
    while (node.l1.get(ISIN).isPresent()) {
      if (System.nanoTime() > deadline) {
        throw new IllegalStateException("the invalidation never arrived");
      }
      Thread.onSpinWait();
    }
  }
}
