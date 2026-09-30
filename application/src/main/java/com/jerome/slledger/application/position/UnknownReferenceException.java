package com.jerome.slledger.application.position;

/**
 * A command or query named a counterparty, security or position that does not exist.
 *
 * <p>It says whether the missing thing is the <em>subject</em> of the request (the position being
 * returned against, the security being quoted) or something the request merely <em>refers</em> to
 * (the counterparty an open names). The web layer answers the first as a missing resource and the
 * second as an unprocessable request, and decides by this, not by parsing the message.
 */
public class UnknownReferenceException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String kind;
  private final boolean subject;

  private UnknownReferenceException(String kind, String id, boolean subject) {
    super("No %s with id %s".formatted(kind, id));
    this.kind = kind;
    this.subject = subject;
  }

  /** The thing the request is about does not exist. */
  public static UnknownReferenceException subject(String kind, String id) {
    return new UnknownReferenceException(kind, id, true);
  }

  /** Something the request refers to does not exist. */
  public static UnknownReferenceException reference(String kind, String id) {
    return new UnknownReferenceException(kind, id, false);
  }

  /** What was missing: {@code "position"}, {@code "counterparty"} or {@code "security"}. */
  public String kind() {
    return kind;
  }

  public boolean isSubject() {
    return subject;
  }
}
