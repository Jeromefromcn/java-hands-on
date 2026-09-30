package com.jerome.slledger.application.saga;

/** A step that the saga's own history forbids, such as reserving for a saga already released. */
public class SagaRefusedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public SagaRefusedException(String message) {
    super(message);
  }
}
