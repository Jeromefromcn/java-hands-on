package com.jerome.slledger.domain.error;

import java.util.Currency;

/**
 * Two amounts in different currencies were combined or compared.
 *
 * <p>The message names both currencies, because the call site that produced it usually holds one of
 * them implicitly — a collateral figure carried down from a configuration file, say — and the
 * useful part of the diagnosis is which pair met.
 */
public final class CurrencyMismatchException extends DomainException {

  private static final long serialVersionUID = 1L;

  public CurrencyMismatchException(Currency left, Currency right) {
    super(
        "Cannot combine amounts in different currencies: %s and %s"
            .formatted(left.getCurrencyCode(), right.getCurrencyCode()));
  }
}
