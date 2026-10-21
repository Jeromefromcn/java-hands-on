package com.jerome.slledger.application.rollout;

import com.jerome.slledger.domain.party.CounterpartyId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Where a counterparty sits for a flag, 0 to 99: the first four bytes of SHA-256 over the flag and
 * the counterparty id, modulo 100. A counterparty is on a rollout of {@code p} percent when its
 * bucket is below {@code p}.
 *
 * <p>Stable: the same counterparty is in the same bucket on every instance and every run, so a
 * statement never switches convention back and forth between days. Salted by the flag: the 5% that
 * try one change are not, by construction, the 5% that try every change. Not {@code
 * String.hashCode}: its value for short similar ids clusters, and it is a JVM detail nobody
 * promised to keep.
 */
public final class Bucket {

  private Bucket() {}

  public static int of(String flag, CounterpartyId counterparty) {
    byte[] digest = sha256(flag + ":" + counterparty.value());
    long first4 =
        ((digest[0] & 0xFFL) << 24)
            | ((digest[1] & 0xFFL) << 16)
            | ((digest[2] & 0xFFL) << 8)
            | (digest[3] & 0xFFL);
    return (int) (first4 % 100);
  }

  public static boolean selected(String flag, CounterpartyId counterparty, int percent) {
    return of(flag, counterparty) < percent;
  }

  private static byte[] sha256(String text) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      // Every Java platform is required to provide SHA-256.
      throw new IllegalStateException(e);
    }
  }
}
