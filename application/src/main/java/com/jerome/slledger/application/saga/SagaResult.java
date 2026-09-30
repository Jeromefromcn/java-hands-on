package com.jerome.slledger.application.saga;

/**
 * How a saga stands after a call.
 *
 * @param failedStep the forward step that failed, for a compensated saga; else {@code null}
 * @param cause what it failed with, or {@code null}
 */
public record SagaResult(Status status, String failedStep, RuntimeException cause) {

  public enum Status {
    /** Every step is done. */
    COMPLETED,
    /** A step failed before the pivot and everything is undone. */
    COMPENSATED,
    /**
     * The position exists; confirming the hold has not landed yet. Recovery will roll it forward.
     */
    PENDING_COMPLETION,
    /** A step failed before the pivot and a compensation failed too. Recovery will finish it. */
    COMPENSATION_INCOMPLETE
  }

  static SagaResult completed() {
    return new SagaResult(Status.COMPLETED, null, null);
  }
}
