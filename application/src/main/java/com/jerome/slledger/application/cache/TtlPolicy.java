package com.jerome.slledger.application.cache;

import java.time.Duration;
import java.util.Objects;
import java.util.Random;

/**
 * How long an entry lives. {@code base} is spread by {@code jitter} in both directions, drawn per
 * entry, so entries stored in the same instant do not all expire in the same instant. A known miss
 * lives {@code negative}.
 *
 * @param jitter a fraction in {@code [0, 1)}; 0.2 makes a 10 s base last between 8 s and 12 s
 */
public record TtlPolicy(Duration base, double jitter, Duration negative) {

  public TtlPolicy {
    Objects.requireNonNull(base, "base");
    Objects.requireNonNull(negative, "negative");
    if (base.isZero() || base.isNegative()) {
      throw new IllegalArgumentException("base TTL must be positive, was " + base);
    }
    if (negative.isZero() || negative.isNegative()) {
      throw new IllegalArgumentException("negative TTL must be positive, was " + negative);
    }
    if (!(jitter >= 0 && jitter < 1)) {
      throw new IllegalArgumentException("jitter must be in [0, 1), was " + jitter);
    }
  }

  /** Never zero: a jitter near 1 could otherwise round a short base down to nothing. */
  public Duration ttlFor(Random random) {
    double factor = 1 + jitter * (2 * random.nextDouble() - 1);
    long nanos = Math.max(1L, Math.round(base.toNanos() * factor));
    return Duration.ofNanos(nanos);
  }

  public Duration negativeTtl() {
    return negative;
  }
}
