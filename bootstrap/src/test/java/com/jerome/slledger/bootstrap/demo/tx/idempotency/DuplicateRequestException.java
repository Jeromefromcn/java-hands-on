package com.jerome.slledger.bootstrap.demo.tx.idempotency;

/** The request key has been seen before; the method did not run. */
public class DuplicateRequestException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public DuplicateRequestException(String key) {
    super("duplicate request: " + key);
  }
}
