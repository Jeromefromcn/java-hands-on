package com.jerome.slledger.application.accrual;

/** A stored position that cannot be restored to a valid one. Skippable: it concerns one row. */
public class CorruptPositionException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String positionId;

  public CorruptPositionException(String positionId, String reason) {
    super("Position %s cannot be restored: %s".formatted(positionId, reason));
    this.positionId = positionId;
  }

  public String positionId() {
    return positionId;
  }
}
