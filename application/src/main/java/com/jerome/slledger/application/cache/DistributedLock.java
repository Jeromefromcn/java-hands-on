package com.jerome.slledger.application.cache;

import java.time.Duration;
import java.util.Optional;

/**
 * A lock that outlives one JVM. It expires by itself after {@code ttl}, so a holder that dies
 * cannot block the others for ever, and that is also its weakness: a holder that is merely paused
 * loses the lock without knowing. Every lease therefore carries a {@linkplain Lease#fencingToken()
 * fencing token} that the write it protects must present to {@link WriteFence}.
 */
public interface DistributedLock {

  /** The lease, or empty when another holder has the lock. Never blocks. */
  Optional<Lease> tryAcquire(String resource, Duration ttl);

  interface Lease {

    String resource();

    /** Strictly increasing across acquisitions of the same resource. */
    long fencingToken();

    /**
     * Releases the lock only if this lease still holds it. Calling it again, or after the lock
     * expired and another holder took it, does nothing.
     */
    void release();
  }
}
