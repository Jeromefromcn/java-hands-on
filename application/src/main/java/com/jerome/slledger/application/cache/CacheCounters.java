package com.jerome.slledger.application.cache;

import java.util.concurrent.atomic.AtomicLong;

/** What a {@link ReadThrough} did, for tests to read and for stage 12 to publish. */
public final class CacheCounters {

  private final AtomicLong hits = new AtomicLong();
  private final AtomicLong misses = new AtomicLong();
  private final AtomicLong loads = new AtomicLong();
  private final AtomicLong loadFailures = new AtomicLong();
  private final AtomicLong tierErrors = new AtomicLong();
  private final AtomicLong lockFallbacks = new AtomicLong();

  void hit() {
    hits.incrementAndGet();
  }

  void miss() {
    misses.incrementAndGet();
  }

  void load() {
    loads.incrementAndGet();
  }

  void loadFailure() {
    loadFailures.incrementAndGet();
  }

  void tierError() {
    tierErrors.incrementAndGet();
  }

  void lockFallback() {
    lockFallbacks.incrementAndGet();
  }

  public long hits() {
    return hits.get();
  }

  public long misses() {
    return misses.get();
  }

  public long loads() {
    return loads.get();
  }

  public long loadFailures() {
    return loadFailures.get();
  }

  /** A tier that threw and was skipped. */
  public long tierErrors() {
    return tierErrors.get();
  }

  /** A rebuild that could not get the lock, waited, and loaded anyway. */
  public long lockFallbacks() {
    return lockFallbacks.get();
  }
}
