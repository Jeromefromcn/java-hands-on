package com.jerome.slledger.adapter.persistence.loan;

/**
 * An accrual rule this adapter cannot store, or a stored rule type it cannot rebuild.
 *
 * <p>An adapter exception, not a {@code DomainException}: the rule is perfectly valid in the
 * domain. What is missing is a table for it. See {@code docs/decisions/06-persistence.md}.
 */
public class UnsupportedAccrualRuleException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public UnsupportedAccrualRuleException(String message) {
    super(message);
  }
}
