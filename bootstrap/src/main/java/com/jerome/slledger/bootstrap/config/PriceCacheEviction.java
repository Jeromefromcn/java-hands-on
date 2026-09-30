package com.jerome.slledger.bootstrap.config;

import com.jerome.slledger.application.messaging.PriceApplied;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Drops a security's cached quote and directory entry once the new price has committed. This is
 * stage 9's rule, "update the store, then delete the entry", with the delete tied to the commit
 * rather than to the caller: evicting before the commit would let a reader repopulate the cache
 * with the old price in between.
 *
 * <p>In-process and best effort. A crash between the commit and this call leaves the old entry to
 * expire by its TTL, which is the staleness bound stage 9 states; nothing depends on the eviction
 * happening.
 */
class PriceCacheEviction {

  private final CachedQuoteSources sources;

  PriceCacheEviction(CachedQuoteSources sources) {
    this.sources = sources;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  void evict(PriceApplied applied) {
    sources.prices().evict(applied.security());
    sources.securities().evict(applied.security());
  }
}
