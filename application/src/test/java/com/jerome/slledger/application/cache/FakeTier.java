package com.jerome.slledger.application.cache;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** An in-memory tier on a clock the test moves, recording the TTL of every put. */
final class FakeTier<K, V> implements CacheTier<K, V> {

  private record Stored<V>(Entry<V> entry, long expiresAtNanos) {}

  private final AtomicLong clockNanos;
  private final Map<K, Stored<V>> store = new ConcurrentHashMap<>();
  private final List<Duration> ttls = java.util.Collections.synchronizedList(new ArrayList<>());

  FakeTier(AtomicLong clockNanos) {
    this.clockNanos = clockNanos;
  }

  @Override
  public Optional<Entry<V>> get(K key) {
    Stored<V> stored = store.get(key);
    if (stored == null || stored.expiresAtNanos() <= clockNanos.get()) {
      return Optional.empty();
    }
    return Optional.of(stored.entry());
  }

  @Override
  public void put(K key, Entry<V> entry, Duration ttl) {
    ttls.add(ttl);
    store.put(key, new Stored<>(entry, clockNanos.get() + ttl.toNanos()));
  }

  @Override
  public void evict(K key) {
    store.remove(key);
  }

  List<Duration> ttls() {
    synchronized (ttls) {
      return List.copyOf(ttls);
    }
  }

  boolean holds(K key) {
    return get(key).isPresent();
  }
}
