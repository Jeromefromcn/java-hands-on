package com.jerome.slledger.application.reconciliation;

/**
 * The four ways the ledger and the custodian can disagree about one position. A position has at
 * most one break per snapshot, the first that applies in this order: quantity, then value, then
 * presence.
 */
public enum BreakKind {

  /** Both have the position and hold different quantities. */
  QUANTITY_MISMATCH,

  /** Both have it, the quantities agree, the values do not (or are in different currencies). */
  VALUE_MISMATCH,

  /** The ledger has an open position the custodian does not. */
  OURS_ONLY,

  /** The custodian holds a position the ledger does not know. */
  THEIRS_ONLY
}
