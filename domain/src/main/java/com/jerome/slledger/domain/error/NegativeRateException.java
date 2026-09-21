package com.jerome.slledger.domain.error;

import java.math.BigDecimal;

/**
 * A rate was offered as a negative number.
 *
 * <p>Negative interest rates exist and this refusal is a scope decision, not a claim that they do
 * not. A negative borrow rate means the lender pays the borrower, which inverts the direction of
 * every accrual in this ledger; admitting it without working through that is how a sign error gets
 * booked. See {@code docs/decisions/02-domain-model.md}.
 */
public final class NegativeRateException extends DomainException {

  private static final long serialVersionUID = 1L;

  public NegativeRateException(BigDecimal annual) {
    super("Rate must not be negative, was %s".formatted(annual.toPlainString()));
  }
}
