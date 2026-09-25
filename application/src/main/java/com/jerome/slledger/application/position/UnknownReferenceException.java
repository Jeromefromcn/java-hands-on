package com.jerome.slledger.application.position;

/** A command named a counterparty, security or position that does not exist. */
public class UnknownReferenceException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public UnknownReferenceException(String kind, String id) {
    super("No %s with id %s".formatted(kind, id));
  }
}
