package com.jerome.slledger.application.port;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * What a submission asked for, reduced to a SHA-256 of its canonical form.
 *
 * <p>Stored with the trade the submission booked, so that a later submission with the same
 * idempotency key can be told apart: the same request retried is replayed, a different request
 * reusing the key is refused. Computed from the command, not from the HTTP body, so JSON field
 * order and whitespace do not matter, and two amounts equal as {@code Money} ({@code 12.5} and
 * {@code 12.50} HKD) are the same request. The idempotency key itself is not part of it.
 */
public record RequestFingerprint(String sha256) {

  private static final Pattern HEX_64 = Pattern.compile("[0-9a-f]{64}");

  public RequestFingerprint {
    Objects.requireNonNull(sha256, "sha256");
    if (!HEX_64.matcher(sha256).matches()) {
      throw new IllegalArgumentException("not a lowercase SHA-256 hex digest: " + sha256);
    }
  }

  /** The fingerprint of a canonical form; the caller owns making that form canonical. */
  public static RequestFingerprint of(String canonical) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
      return new RequestFingerprint(HexFormat.of().formatHex(digest));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("every JDK ships SHA-256", e);
    }
  }
}
