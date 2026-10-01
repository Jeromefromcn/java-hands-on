package com.jerome.slledger.application.security;

/**
 * The caller asked for something outside what it may see or do. Answered as {@code 403}; a row that
 * merely is not the caller's is not this, it is {@code UnknownReferenceException}, so that a
 * foreign id and a missing id are indistinguishable.
 */
public class ForbiddenException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public ForbiddenException(String message) {
    super(message);
  }
}
