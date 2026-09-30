package com.jerome.slledger.adapter.persistence.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import com.jerome.slledger.application.cache.CacheTier;
import com.jerome.slledger.application.cache.Entry;
import java.time.Duration;
import java.util.Optional;

/**
 * The per-instance tier: a Caffeine cache bounded by {@code maximumSize}, each entry expiring after
 * the TTL it was stored with.
 *
 * <p>The bound is not optional. An unbounded local cache keeps every key it has ever seen, and the
 * keys of a price cache are supplied by callers: a scanner walking ISINs grows it until the heap
 * ends. {@code CaffeineTierTest.reproducesUnboundedGrowth} shows the difference.
 *
 * <p>The ticker is injectable, so a test moves time instead of waiting for it. Removal work runs on
 * the calling thread, which keeps a test's view of the cache deterministic; the production cost is
 * a few microseconds on a write, which is what the tier's bound buys.
 */
public final class CaffeineTier<K, V> implements CacheTier<K, V> {

  private record Timed<V>(Entry<V> entry, long ttlNanos) {}

  private final Cache<K, Timed<V>> cache;

  public CaffeineTier(long maximumSize, Ticker ticker) {
    if (maximumSize < 1) {
      throw new IllegalArgumentException("maximumSize must be at least 1, was " + maximumSize);
    }
    this.cache =
        Caffeine.newBuilder()
            .maximumSize(maximumSize)
            .ticker(ticker)
            .executor(Runnable::run)
            .expireAfter(
                new Expiry<K, Timed<V>>() {
                  @Override
                  public long expireAfterCreate(K key, Timed<V> value, long currentTime) {
                    return value.ttlNanos();
                  }

                  @Override
                  public long expireAfterUpdate(
                      K key, Timed<V> value, long currentTime, long currentDuration) {
                    return value.ttlNanos();
                  }

                  @Override
                  public long expireAfterRead(
                      K key, Timed<V> value, long currentTime, long currentDuration) {
                    return currentDuration;
                  }
                })
            .recordStats()
            .build();
  }

  public CaffeineTier(long maximumSize) {
    this(maximumSize, Ticker.systemTicker());
  }

  @Override
  public Optional<Entry<V>> get(K key) {
    return Optional.ofNullable(cache.getIfPresent(key)).map(Timed::entry);
  }

  @Override
  public void put(K key, Entry<V> entry, Duration ttl) {
    cache.put(key, new Timed<>(entry, ttl.toNanos()));
  }

  @Override
  public void evict(K key) {
    cache.invalidate(key);
  }

  /** Runs pending removals, so {@link #size()} counts only what a read would still find. */
  public void cleanUp() {
    cache.cleanUp();
  }

  public long size() {
    return cache.estimatedSize();
  }

  public CacheStats stats() {
    return cache.stats();
  }
}
