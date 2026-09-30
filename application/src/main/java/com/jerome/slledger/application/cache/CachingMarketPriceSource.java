package com.jerome.slledger.application.cache;

import com.jerome.slledger.application.port.MarketPriceSource;
import com.jerome.slledger.application.position.UnknownReferenceException;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.money.Money;
import java.util.List;
import java.util.Optional;
import java.util.Random;

/**
 * The market data source behind a {@link ReadThrough}. Nothing above it changes: {@code QuotePrice}
 * still checks the currency of what it is handed, cached or not.
 *
 * <p>The source's "I do not know this security" is a cacheable answer (an {@link Entry.Absent} with
 * the short negative TTL). Its "I could not answer" and "I refuse this answer" are not: they
 * propagate and store nothing, so an outage is not remembered after it ends.
 *
 * <p>{@code invalidation} may be null: with no channel, another instance's copy lives until its own
 * TTL, which is the staleness bound the decision record states.
 */
public final class CachingMarketPriceSource implements MarketPriceSource {

  private final ReadThrough<Isin, Money> cache;
  private final Invalidation<Isin> invalidation;

  public CachingMarketPriceSource(
      MarketPriceSource source,
      List<ReadThrough.Layer<Isin, Money>> layers,
      Random random,
      ReadThrough.Rebuild<Isin> rebuild,
      Invalidation<Isin> invalidation) {
    this.invalidation = invalidation;
    this.cache =
        new ReadThrough<>(
            layers,
            isin -> {
              try {
                return Optional.of(source.priceOf(isin));
              } catch (UnknownReferenceException unknown) {
                return Optional.empty();
              }
            },
            random,
            rebuild);
  }

  @Override
  public Money priceOf(Isin isin) {
    return cache
        .get(isin)
        .orElseThrow(() -> UnknownReferenceException.subject("security", isin.value()));
  }

  /**
   * The price changed: removes it from every tier, tells a load already running not to store its
   * result, and asks the other instances to drop their nearest-tier copy.
   */
  public void evict(Isin isin) {
    cache.evict(isin);
    if (invalidation != null) {
      invalidation.publish(isin);
    }
  }

  /** Another instance says the price changed: drops this instance's nearest-tier copy only. */
  public void evictLocal(Isin isin) {
    cache.evictNearest(isin);
  }

  public CacheCounters counters() {
    return cache.counters();
  }
}
