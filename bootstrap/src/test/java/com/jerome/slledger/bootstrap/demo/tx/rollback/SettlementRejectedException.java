package com.jerome.slledger.bootstrap.demo.tx.rollback;

/** Checked on purpose: the kind of exception Spring's default rollback rule lets commit. */
public class SettlementRejectedException extends Exception {

  private static final long serialVersionUID = 1L;

  public SettlementRejectedException(String positionId) {
    super("settlement rejected for position " + positionId);
  }
}
