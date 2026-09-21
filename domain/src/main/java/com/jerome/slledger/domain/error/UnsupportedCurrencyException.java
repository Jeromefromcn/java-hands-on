package com.jerome.slledger.domain.error;

import java.util.Currency;

/**
 * A currency that cannot hold an amount of money was offered.
 *
 * <p>{@link Currency#getDefaultFractionDigits()} returns {@code -1} for the ISO 4217
 * pseudo-currencies: XAU and the other metals, XDR for special drawing rights, XTS reserved for
 * testing, and XXX meaning "no currency". They have no minor unit, so "an amount at its currency's
 * scale" is not defined for them.
 *
 * <p>Refusing rather than defaulting to zero digits is the point. {@code setScale(-1, HALF_UP)} is
 * perfectly legal and rounds to the nearest <em>ten</em>, so 1,234.56 XAU would be held as 1,230
 * and this project's one stated invariant would fail without a single exception being raised. Gold
 * can be collateral; it is priced in a currency, and it is not one.
 */
public final class UnsupportedCurrencyException extends DomainException {

  private static final long serialVersionUID = 1L;

  public UnsupportedCurrencyException(Currency currency) {
    super(
        "%s has no minor unit (getDefaultFractionDigits() == %d) and cannot denominate money"
            .formatted(currency.getCurrencyCode(), currency.getDefaultFractionDigits()));
  }
}
