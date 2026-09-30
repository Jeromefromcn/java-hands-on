package com.jerome.slledger.application.collateral;

/** A hold that the ledger will not make or change, and why. */
public class CollateralRefusedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public enum Reason {
    INSUFFICIENT_COLLATERAL,
    NO_ACCOUNT,
    ALREADY_CANCELLED,
    ALREADY_CONFIRMED,
    NEVER_TRIED
  }

  private final Reason reason;

  public CollateralRefusedException(Reason reason, String xid) {
    super("Collateral hold %s refused: %s".formatted(xid, reason));
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
