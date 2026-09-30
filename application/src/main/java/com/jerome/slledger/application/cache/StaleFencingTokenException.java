package com.jerome.slledger.application.cache;

/** A write carried a fencing token older than one the store has already admitted. */
public class StaleFencingTokenException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String resource;
  private final long token;

  public StaleFencingTokenException(String resource, long token) {
    super("Fencing token %d is stale for %s".formatted(token, resource));
    this.resource = resource;
    this.token = token;
  }

  public String resource() {
    return resource;
  }

  public long token() {
    return token;
  }
}
