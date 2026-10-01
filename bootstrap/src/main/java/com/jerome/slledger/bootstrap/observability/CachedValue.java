package com.jerome.slledger.bootstrap.observability;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleSupplier;

/**
 * A gauge that reads a database must not run its query once per scrape per instance per second.
 * This keeps the last value of each key for a short time.
 */
final class CachedValue {

  private CachedValue() {}

  static final class Clocked {
    private record Held(double value, long at) {}

    private final long ttlMillis;
    private final Map<String, Held> held = new ConcurrentHashMap<>();

    Clocked(long ttlMillis) {
      this.ttlMillis = ttlMillis;
    }

    /** The cached value, or a fresh one; NaN if reading it fails, which a gauge shows as absent. */
    double get(String key, DoubleSupplier read) {
      long now = System.currentTimeMillis();
      Held current = held.get(key);
      if (current != null && now - current.at() < ttlMillis) {
        return current.value();
      }
      double value;
      try {
        value = read.getAsDouble();
      } catch (RuntimeException failed) {
        return Double.NaN;
      }
      held.put(key, new Held(value, now));
      return value;
    }
  }
}
