package com.jerome.slledger.domain.error;

import java.math.BigDecimal;

/**
 * An accrual was attempted against a negative outstanding value.
 *
 * <p>A position cannot owe less than nothing. Booking the fee anyway would produce a credit entry
 * that looks exactly like a legitimate rebate, which is the worst kind of wrong number: one that
 * reconciles.
 */
public final class NegativeOutstandingValueException extends DomainException {

  private static final long serialVersionUID = 1L;

  public NegativeOutstandingValueException(BigDecimal value) {
    super("Outstanding value must not be negative, was %s".formatted(value.toPlainString()));
  }
}
