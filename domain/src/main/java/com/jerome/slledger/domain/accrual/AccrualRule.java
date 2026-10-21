package com.jerome.slledger.domain.accrual;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;

/**
 * How one day's fee is computed from an outstanding market value.
 *
 * <p>The strategy this domain varies. Without it the three pricing shapes below would be branches
 * inside the accrual loop, and adding a fourth would mean editing the batch job rather than writing
 * a class.
 */
public interface AccrualRule {

  /**
   * Sixteen significant digits. BigDecimal division needs to be told a precision or it throws on a
   * non-terminating expansion, and a third of the rates in this domain produce one. Sixteen is far
   * more than any currency books, so the choice of precision never reaches the booked amount — the
   * rounding that does is in {@link AccrualEntry#accrue}.
   */
  MathContext PRECISION = MathContext.DECIMAL64;

  /**
   * The year's fee on the given outstanding market value at the rate of the given day,
   * <strong>unrounded</strong> and undivided: the day count is applied once, by {@link #dailyFee}.
   *
   * <p>Returns a bare BigDecimal on purpose. Money is always at its currency's scale, so returning
   * one here would round inside the rule, and a tiered rule would then round once per tier. The
   * single rounding point is {@link AccrualEntry#accrue}. See {@code
   * docs/decisions/02-domain-model.md}.
   */
  BigDecimal annualFee(BigDecimal outstandingValue, LocalDate on);

  /** The day's fee on ACT/365F, the ledger's convention before stage 14; unrounded. */
  default BigDecimal dailyFee(BigDecimal outstandingValue, LocalDate on) {
    return dailyFee(outstandingValue, on, DayCount.ACT_365F);
  }

  /**
   * The day's fee under a day count, <strong>unrounded</strong>: the annual fee divided once by the
   * day count's basis. One division per fee, whatever the rule's shape, so the rule and the
   * convention cannot compound each other's precision. Stage 14; before it, each rule divided by a
   * constant 365 itself. See {@code docs/decisions/14-delivery.md}.
   */
  default BigDecimal dailyFee(BigDecimal outstandingValue, LocalDate on, DayCount dayCount) {
    return annualFee(outstandingValue, on).divide(dayCount.basis(), PRECISION);
  }
}
