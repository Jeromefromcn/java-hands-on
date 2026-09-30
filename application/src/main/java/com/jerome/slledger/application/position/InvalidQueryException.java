package com.jerome.slledger.application.position;

/** A read asked for something the ledger does not answer, such as a reversed date range. */
public class InvalidQueryException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public InvalidQueryException(String message) {
    super(message);
  }
}
