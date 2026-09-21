package com.jerome.slledger.domain.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * An amount of money in a stated currency, always held at that currency's scale.
 *
 * <p>The compact constructor normalises on every path in, which buys two things. The record's
 * generated {@code equals} becomes correct: {@code BigDecimal.equals} compares scale as well as
 * value, so without normalisation 10.00 HKD and 10.0 HKD would be two different amounts of money.
 * And the rounding policy stops being a discipline and becomes a property of the type — an
 * unrounded {@code Money} cannot be constructed.
 *
 * <p>The cost is that intermediate high-precision arithmetic cannot use this type. That is
 * deliberate: it forces the one place where a computed figure becomes a booked amount to be
 * visible. See {@code docs/decisions/02-domain-model.md}.
 */
public record Money(BigDecimal amount, Currency currency) {

  /**
   * Money is booked HALF_UP. The contract wording counterparties read says "rounded to the nearest
   * cent"; HALF_EVEN would be defensible statistically and indefensible in that conversation.
   */
  private static final RoundingMode BOOKING = RoundingMode.HALF_UP;

  public Money {
    Objects.requireNonNull(amount, "amount");
    Objects.requireNonNull(currency, "currency");
    amount = amount.setScale(currency.getDefaultFractionDigits(), BOOKING);
  }

  public static Money of(BigDecimal amount, Currency currency) {
    return new Money(amount, currency);
  }

  public static Money of(String amount, Currency currency) {
    return new Money(new BigDecimal(Objects.requireNonNull(amount, "amount")), currency);
  }

  public static Money zero(Currency currency) {
    return new Money(BigDecimal.ZERO, currency);
  }
}
