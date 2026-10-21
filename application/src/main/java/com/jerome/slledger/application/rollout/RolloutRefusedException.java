package com.jerome.slledger.application.rollout;

/**
 * A rollout change the rules do not allow. Answered as {@code 409}: the request is well formed and
 * the caller may make it, but not now or not by this person.
 */
public class RolloutRefusedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Why, as a stable code for the problem type and the audit row. */
  public enum Reason {
    /** The checker is the maker. */
    SAME_PERSON,
    /** The first step from zero is above the first-step ceiling. */
    FIRST_STEP_TOO_LARGE,
    /** The current step has not been in effect for the observation period. */
    OBSERVATION_PERIOD,
    /** A raise that does not raise, or a lowering that does not lower. */
    NOT_A_RAISE,
    NOT_A_LOWERING,
    /** The rollout moved after the change was proposed, or the change was already decided. */
    STALE
  }

  private final Reason reason;

  public RolloutRefusedException(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  public Reason reason() {
    return reason;
  }
}
