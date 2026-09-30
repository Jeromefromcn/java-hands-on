package com.jerome.slledger.application.quote;

import com.jerome.slledger.application.port.MarketDataRejectedException;
import com.jerome.slledger.application.port.MarketPriceSource;
import com.jerome.slledger.application.port.SecurityDirectory;
import com.jerome.slledger.application.position.UnknownReferenceException;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.instrument.Security;
import com.jerome.slledger.domain.money.Money;

/**
 * The live price of a security, from the external source.
 *
 * <p>Deliberately <strong>not</strong> transactional. The security is read first, in the
 * directory's own short read, so an unknown ISIN costs no remote call; the remote call then runs
 * with no transaction open. A transaction around it would hold a database connection for as long as
 * the source takes, and a slow source would drain the pool as well as the request threads.
 */
public class QuotePrice {

  private final SecurityDirectory securities;
  private final MarketPriceSource source;

  public QuotePrice(SecurityDirectory securities, MarketPriceSource source) {
    this.securities = securities;
    this.source = source;
  }

  /**
   * @throws UnknownReferenceException if the ledger does not know the security
   * @throws MarketDataRejectedException if the price is not in the security's currency
   */
  public Money quote(Isin isin) {
    Security security =
        securities
            .find(isin)
            .orElseThrow(() -> UnknownReferenceException.subject("security", isin.value()));
    Money price = source.priceOf(isin);
    if (!price.currency().equals(security.currency())) {
      throw new MarketDataRejectedException(
          "The source priced %s in %s; the security trades in %s"
              .formatted(isin.value(), price.currency(), security.currency()));
    }
    return price;
  }
}
