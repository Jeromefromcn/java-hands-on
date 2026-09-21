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
   * ACT/365 fixed. This is a choice, not a law: USD money-market instruments conventionally accrue
   * on ACT/360, which makes every figure in this ledger about 1.4 percent larger. It is a single
   * constant today because this ledger books in HKD; the day it books in two conventions, this
   * becomes a property of the rule and not of the interface.
   */
  BigDecimal DAY_COUNT_BASIS = new BigDecimal("365");

  /**
   * Sixteen significant digits. BigDecimal division needs to be told a precision or it throws on a
   * non-terminating expansion, and a third of the rates in this domain produce one. Sixteen is far
   * more than any currency books, so the choice of precision never reaches the booked amount — the
   * rounding that does is in {@link AccrualEntry#accrue}.
   */
  MathContext PRECISION = MathContext.DECIMAL64;

  /**
   * The day's fee on the given outstanding market value, <strong>unrounded</strong>.
   *
   * <p>Returns a bare BigDecimal on purpose. Money is always at its currency's scale, so returning
   * one here would round inside the rule, and a tiered rule would then round once per tier. The
   * single rounding point is {@link AccrualEntry#accrue}. See {@code
   * docs/decisions/02-domain-model.md}.
   */
  BigDecimal dailyFee(BigDecimal outstandingValue, LocalDate on);
}
