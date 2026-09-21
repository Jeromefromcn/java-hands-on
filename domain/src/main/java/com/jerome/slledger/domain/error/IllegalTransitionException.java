package com.jerome.slledger.domain.error;

/**
 * A lifecycle transition was attempted that the state machine does not have an arrow for.
 *
 * <p>Distinct from {@link ExcessiveReturnException} on purpose: one says the request was too large,
 * the other says the request was not available at all. Collapsing them into one exception would
 * make "return 1 share against a closed position" and "return 6001 of 6000" look like the same
 * fault to everything downstream, and they have different causes and different fixes.
 */
public final class IllegalTransitionException extends DomainException {

  private static final long serialVersionUID = 1L;

  public IllegalTransitionException(String from, String attempted) {
    super("A position in %s cannot %s".formatted(from, attempted));
  }
}
