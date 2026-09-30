package com.jerome.slledger.application.batch;

import com.jerome.slledger.application.cache.DistributedLock;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;

/**
 * Runs something on at most one instance at a time: the instance that holds the lock for the
 * resource. The lock is coarse mutual exclusion; what makes a holder that lost its lock harmless is
 * the fencing token its lease carries, which the protected writes present to the store.
 */
public class FencedRun {

  private final DistributedLock lock;
  private final Duration ttl;

  /**
   * @param ttl how long a lease lasts without renewal. It must exceed the longest run, or the lock
   *     expires under a live run: the fence keeps that safe, and the cost is a run that another
   *     instance takes over and the first one is refused mid-way.
   */
  public FencedRun(DistributedLock lock, Duration ttl) {
    this.lock = lock;
    this.ttl = ttl;
  }

  /**
   * @return the body's result, or empty without calling it when another instance holds the lock
   */
  public <T> Optional<T> launch(String resource, Function<DistributedLock.Lease, T> body) {
    Optional<DistributedLock.Lease> lease = lock.tryAcquire(resource, ttl);
    if (lease.isEmpty()) {
      return Optional.empty();
    }
    try {
      return Optional.of(body.apply(lease.get()));
    } finally {
      lease.get().release();
    }
  }
}
