package com.jerome.slledger.domain.party;

import com.jerome.slledger.domain.money.Money;
import java.util.Objects;

/**
 * How much one counterparty has on, in one currency: how many positions, and how much collateral
 * across them.
 *
 * <p>Computed, never stored. A cached exposure figure is a number that can disagree with the
 * positions it summarises, and the disagreement surfaces the day somebody needs it to be right.
 */
public record ExposureSummary(long positionCount, Money totalCollateral) {

  public ExposureSummary {
    Objects.requireNonNull(totalCollateral, "totalCollateral");
    if (positionCount < 0) {
      throw new IllegalArgumentException(
          "A position count cannot be negative, was " + positionCount);
    }
  }
}
