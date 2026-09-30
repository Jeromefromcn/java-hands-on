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
 */
public final class CachingMarketPriceSource implements MarketPriceSource {

  private final ReadThrough<Isin, Money> cache;

  public CachingMarketPriceSource(
      MarketPriceSource source,
      List<ReadThrough.Layer<Isin, Money>> layers,
      Random random,
      ReadThrough.Rebuild<Isin> rebuild) {
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
   * Removes the price from every tier, and tells a load already running not to store its result.
   */
  public void evict(Isin isin) {
    cache.evict(isin);
  }

  public CacheCounters counters() {
    return cache.counters();
  }
}
