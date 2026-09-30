package com.jerome.slledger.application.messaging;

import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.money.Money;

/** Writes a security's market price, gated by the version the feed assigned to it. */
public interface SecurityPrices {

  /**
   * Stores {@code price} if {@code version} is higher than the stored one.
   *
   * @return {@code true} if it was stored; {@code false} if the stored version is the same or
   *     higher, which is a duplicate or an update that arrived late, and is not an error
   * @throws com.jerome.slledger.application.position.UnknownReferenceException if the ledger holds
   *     no such security
   * @throws com.jerome.slledger.domain.error.CurrencyMismatchException if the price is not in the
   *     security's currency
   */
  boolean applyIfNewer(Isin security, Money price, long version);
}
