package com.jerome.slledger.domain.error;

/**
 * A return was attempted for more than the position still owes.
 *
 * <p>Both quantities are in the message. "Cannot return 6001" without saying what was outstanding
 * sends the reader to the database to find out whether the request or the ledger was wrong.
 */
public final class ExcessiveReturnException extends DomainException {

  private static final long serialVersionUID = 1L;

  public ExcessiveReturnException(long requested, long outstanding) {
    super(
        "Cannot return %d against an outstanding quantity of %d".formatted(requested, outstanding));
  }
}
