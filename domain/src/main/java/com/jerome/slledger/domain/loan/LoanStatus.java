package com.jerome.slledger.domain.loan;

/** Where a position is in its life. The only legal path is OPEN -> PARTIALLY_RETURNED -> CLOSED. */
public enum LoanStatus {
  /** Nothing has been returned yet. */
  OPEN,

  /** Some has been returned; a balance is still outstanding. */
  PARTIALLY_RETURNED,

  /** Nothing is outstanding. Terminal. */
  CLOSED
}
