package com.jerome.slledger.application.position;

/** A command named a counterparty, security or position that does not exist. */
public class UnknownReferenceException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String kind;

  public UnknownReferenceException(String kind, String id) {
    super("No %s with id %s".formatted(kind, id));
    this.kind = kind;
  }

  /**
   * What was missing: {@code "position"}, {@code "counterparty"} or {@code "security"}. The web
   * layer answers a missing resource in the URL differently from a missing reference in a body, and
   * decides by this, not by parsing the message.
   */
  public String kind() {
    return kind;
  }
}
