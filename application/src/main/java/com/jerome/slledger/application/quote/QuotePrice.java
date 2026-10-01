package com.jerome.slledger.application.quote;

import com.jerome.slledger.application.observability.LedgerMetrics;
import com.jerome.slledger.application.observability.LedgerMetrics.QuoteResult;
import com.jerome.slledger.application.port.MarketDataRejectedException;
import com.jerome.slledger.application.port.MarketDataUnavailableException;
import com.jerome.slledger.application.port.MarketPriceSource;
import com.jerome.slledger.application.port.SecurityDirectory;
import com.jerome.slledger.application.position.UnknownReferenceException;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.instrument.Security;
import com.jerome.slledger.domain.money.Money;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * The live price of a security, from the external source.
 *
 * <p>Deliberately <strong>not</strong> transactional. The security is read first, in the
 * directory's own short read, so an unknown ISIN costs no remote call; the remote call then runs
 * with no transaction open. A transaction around it would hold a database connection for as long as
 * the source takes, and a slow source would drain the pool as well as the request threads.
 *
 * <p>When the source cannot give a price (it did not answer, it answered something refused, or this
 * instance refused to ask), the last price it gave is returned instead if one is younger than the
 * maximum age and is in the security's currency. That quote says it is stale and when it was true;
 * it is also logged and counted. A price older than the maximum is not "better than nothing": the
 * original failure is thrown. Only this display path degrades: opening and returning a position
 * read the security row's price and never reach this class.
 */
public class QuotePrice {

  private static final Log log = LogFactory.getLog(QuotePrice.class);

  private final SecurityDirectory securities;
  private final MarketPriceSource source;
  private final LastKnownPrices lastKnown;
  private final Clock clock;
  private final Duration staleMaxAge;
  private final LedgerMetrics metrics;

  /** No degradation: a failed source is a failed quote. */
  public QuotePrice(SecurityDirectory securities, MarketPriceSource source) {
    this(
        securities,
        source,
        LastKnownPrices.NONE,
        Clock.systemUTC(),
        Duration.ZERO,
        LedgerMetrics.NONE);
  }

  /**
   * @param staleMaxAge the oldest last-known price that may be returned; zero turns degradation off
   */
  public QuotePrice(
      SecurityDirectory securities,
      MarketPriceSource source,
      LastKnownPrices lastKnown,
      Clock clock,
      Duration staleMaxAge,
      LedgerMetrics metrics) {
    this.securities = securities;
    this.source = source;
    this.lastKnown = lastKnown;
    this.clock = clock;
    this.staleMaxAge = staleMaxAge;
    this.metrics = metrics;
  }

  /**
   * @throws UnknownReferenceException if the ledger does not know the security
   * @throws MarketDataRejectedException if the price is not in the security's currency
   */
  public Quote quote(Isin isin) {
    Security security =
        securities
            .find(isin)
            .orElseThrow(() -> UnknownReferenceException.subject("security", isin.value()));
    Money price;
    try {
      price = source.priceOf(isin);
    } catch (MarketDataUnavailableException
        | MarketDataRejectedException
        | OverloadedException failure) {
      return degrade(isin, security, failure);
    }
    if (!price.currency().equals(security.currency())) {
      metrics.quoteServed(QuoteResult.FAILED);
      throw new MarketDataRejectedException(
          "The source priced %s in %s; the security trades in %s"
              .formatted(isin.value(), price.currency(), security.currency()));
    }
    lastKnown.remember(isin, price, clock.instant());
    metrics.quoteServed(QuoteResult.LIVE);
    return Quote.live(price);
  }

  private Quote degrade(Isin isin, Security security, RuntimeException failure) {
    Optional<LastKnownPrices.Stamped> known =
        staleMaxAge.isZero() ? Optional.empty() : lastKnown.find(isin);
    Instant now = clock.instant();
    if (known.isPresent()
        && !Duration.between(known.get().at(), now).minus(staleMaxAge).isPositive()
        && known.get().price().currency().equals(security.currency())) {
      log.warn(
          "Serving a stale price for "
              + isin.value()
              + ", "
              + Duration.between(known.get().at(), now).toSeconds()
              + " s old: "
              + failure.getClass().getSimpleName());
      metrics.quoteServed(QuoteResult.STALE);
      return Quote.stale(known.get().price(), known.get().at());
    }
    metrics.quoteServed(QuoteResult.FAILED);
    throw failure;
  }
}
