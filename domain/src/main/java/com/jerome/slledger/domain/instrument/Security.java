package com.jerome.slledger.domain.instrument;

import com.jerome.slledger.domain.error.CurrencyMismatchException;
import com.jerome.slledger.domain.money.Money;
import java.util.Currency;
import java.util.Objects;

/**
 * A lendable security: its identifier, its listing currency and its current
 * market price.
 *
 * <p>
 * The compact constructor checks that the price is denominated in the
 * security's own currency.
 * This is the first type in the domain that could have broken the money
 * invariant quietly — a
 * security listed in HKD carrying a price stamped USD is internally consistent,
 * reconciles against
 * nothing, and nothing downstream would have questioned it.
 *
 * <p>
 * All instrument names in this repository are synthetic.
 */
public record Security(Isin isin, String name, Currency currency, Money marketPrice) {

  public Security {
    Objects.requireNonNull(isin, "isin");
    Objects.requireNonNull(currency, "currency");
    Objects.requireNonNull(marketPrice, "marketPrice");
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("A security needs a name");
    }
    if (!currency.equals(marketPrice.currency())) {
      throw new CurrencyMismatchException(currency, marketPrice.currency());
    }
    if (marketPrice.isNegative()) {
      throw new IllegalArgumentException(
          "Market price must not be negative, was " + marketPrice.amount().toPlainString());
    }
  }
}
