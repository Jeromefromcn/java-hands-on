package com.jerome.slledger.application.accrual;

import com.jerome.slledger.domain.accrual.AccrualEntry;
import com.jerome.slledger.domain.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * One position's fee for one day: the outstanding market value at the position's price, through the
 * position's rule, rounded once by {@link AccrualEntry#accrue}.
 *
 * <p>Pure: no clock, no store. A corrupt candidate and any refusal of the domain are exceptions
 * that concern this position only.
 */
public final class AccrueDay {

  private AccrueDay() {}

  /**
   * @throws CorruptPositionException the row could not be restored
   * @throws com.jerome.slledger.domain.error.DomainException the domain refused the value
   */
  public static AccrualEntry accrue(AccrualCandidate candidate, LocalDate businessDate) {
    return switch (candidate) {
      case AccrualCandidate.Corrupt corrupt ->
          throw new CorruptPositionException(corrupt.positionId(), corrupt.reason());
      case AccrualCandidate.Valid valid -> {
        // Whole quantity times a price at currency scale: exact, so the mode cannot matter; it is
        // named because Money.multiply refuses to pick one.
        Money value =
            valid
                .unitPrice()
                .multiply(
                    BigDecimal.valueOf(valid.position().outstandingQuantity()),
                    RoundingMode.HALF_UP);
        yield AccrualEntry.accrue(
            valid.position().id(), value, valid.position().accrualRule(), businessDate);
      }
    };
  }
}
