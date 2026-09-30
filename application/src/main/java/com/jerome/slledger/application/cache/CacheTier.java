package com.jerome.slledger.application.cache;

import java.time.Duration;
import java.util.Optional;

/**
 * One level of a cache. Every {@code put} carries its own TTL, because the read-through jitters
 * each entry's lifetime and gives a known miss a shorter one than a value.
 */
public interface CacheTier<K, V> {

  Optional<Entry<V>> get(K key);

  void put(K key, Entry<V> entry, Duration ttl);

  void evict(K key);
}
