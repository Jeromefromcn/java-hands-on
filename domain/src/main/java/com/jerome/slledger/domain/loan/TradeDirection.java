package com.jerome.slledger.domain.loan;

/** Which leg of a loan a trade is. */
public enum TradeDirection {
  /** The lender delivers securities; the borrower delivers collateral. */
  BORROW,

  /** The borrower returns securities; the lender releases collateral. */
  RETURN
}
