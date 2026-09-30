package com.jerome.slledger.application.reconciliation;

/** What is to happen to a break. Each kind of break has a fixed first disposition. */
public enum Disposition {

  /** A value difference inside the tolerance: recorded, accepted, no action. */
  AUTO_ACCEPTED,

  /**
   * Ledger-only, still inside the custodian's settlement window: look again at the next snapshot.
   */
  PENDING_SETTLEMENT,

  /** Ledger-only past the window: the settlement ladder resubmits it, a bounded number of times. */
  RETRY_SETTLEMENT,

  /**
   * Needs a person and has no automatic path: a quantity difference, a value difference beyond
   * tolerance, a position only the custodian holds.
   */
  MANUAL,

  /** The ladder ran out of attempts: a person now. */
  ESCALATED,

  /** Closed: the ladder's resubmission settled it, or a person did. */
  RESOLVED
}
