package com.jerome.slledger.adapter.web.error;

import java.net.URI;
import java.util.Locale;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/**
 * The {@code type} of every error this service answers with: {@code urn:sl-ledger:problem:<kind>}.
 *
 * <p>A URN, not a URL: it names the kind stably without pointing at a host this project does not
 * own. Clients branch on it, never on {@code detail}, which is for people.
 */
public final class Problems {

  public static final String PREFIX = "urn:sl-ledger:problem:";

  private Problems() {}

  public static URI type(String kind) {
    return URI.create(PREFIX + kind);
  }

  public static ProblemDetail of(HttpStatusCode status, String kind, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setType(type(kind));
    return problem;
  }

  /**
   * A kind for the framework's own errors, from the status: 415 is {@code unsupported-media-type}.
   */
  static String kindOf(HttpStatusCode status) {
    org.springframework.http.HttpStatus known =
        org.springframework.http.HttpStatus.resolve(status.value());
    return known == null
        ? "http-" + status.value()
        : known.name().toLowerCase(Locale.ROOT).replace('_', '-');
  }
}
