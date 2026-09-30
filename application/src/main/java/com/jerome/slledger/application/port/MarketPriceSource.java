package com.jerome.slledger.application.port;

import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.money.Money;

/**
 * The external market data source: the current price of one security. A remote call, so it can be
 * slow, fail, or answer something this ledger will not accept; each has its own exception.
 */
public interface MarketPriceSource {

  /**
   * @throws MarketDataUnavailableException the source did not answer in time, or could not be
   *     reached
   * @throws MarketDataRejectedException the source answered with an error or with a price this
   *     ledger refuses to read
   * @throws com.jerome.slledger.application.position.UnknownReferenceException the source does not
   *     know the security
   */
  Money priceOf(Isin isin);
}
