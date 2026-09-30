package com.jerome.slledger.application.quote;

/** Every price request in a batch was refused by the executor: the service is shedding load. */
public class OverloadedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public OverloadedException(String message) {
    super(message);
  }
}
