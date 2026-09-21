package com.jerome.slledger.domain.party;

import com.jerome.slledger.domain.error.InsufficientCreditException;
import com.jerome.slledger.domain.money.Money;
import java.util.Objects;

/**
 * The most a counterparty may have drawn at once, in one currency.
 *
 * <p>One currency, and no conversion. A limit in HKD cannot absorb an exposure in USD: putting an
 * FX rate inside a credit check would mean a refusal depends on a number nobody recorded, and the
 * answer to "why was this trade rejected at 09:14" becomes unreconstructable. The refusal is a
 * {@code CurrencyMismatchException}, raised by {@link Money#plus} rather than by this class.
 */
public record CreditLimit(Money ceiling) {

  public CreditLimit {
    Objects.requireNonNull(ceiling, "ceiling");
    if (ceiling.isNegative()) {
      throw new IllegalArgumentException(
          "A credit limit must not be negative, was " + ceiling.amount().toPlainString());
    }
  }

  /**
   * Refuses the request if drawing it would take the counterparty past this ceiling.
   *
   * <p>At the ceiling exactly is allowed. A limit is a maximum, not an open interval, and the
   * boundary is tested a cent either side because that is where an off-by-one lives.
   */
  public void checkCanAbsorb(Money existingExposure, Money requested) {
    Money afterDrawing = existingExposure.plus(requested);
    if (afterDrawing.isGreaterThan(ceiling)) {
      throw new InsufficientCreditException(ceiling, existingExposure, requested);
    }
  }
}
