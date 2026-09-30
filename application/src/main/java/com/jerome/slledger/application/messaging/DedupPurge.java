package com.jerome.slledger.application.messaging;

import java.time.Clock;
import java.time.Duration;
import org.springframework.transaction.annotation.Transactional;

/**
 * Forgets consumed message ids older than the retention window.
 *
 * <p>The window has to be longer than the longest a message can be redelivered. A replay can only
 * reach messages still in the topic, so the rule is {@code ttl > topic retention}: purge sooner and
 * a duplicate that arrives after the purge is acted on again; purge never and the table grows
 * without bound.
 */
public class DedupPurge {

  private final ConsumedMessages consumed;
  private final Clock clock;
  private final Duration ttl;

  public DedupPurge(ConsumedMessages consumed, Clock clock, Duration ttl) {
    if (ttl.isZero() || ttl.isNegative()) {
      throw new IllegalArgumentException("ttl must be positive, was " + ttl);
    }
    this.consumed = consumed;
    this.clock = clock;
    this.ttl = ttl;
  }

  /**
   * @return how many ids were forgotten
   */
  @Transactional
  public int run() {
    return consumed.purgeBefore(clock.instant().minus(ttl));
  }
}
