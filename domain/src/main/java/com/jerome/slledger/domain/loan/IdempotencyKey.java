package com.jerome.slledger.domain.loan;

import com.jerome.slledger.domain.error.MalformedIdentifierException;

/**
 * Identifies a submission, not a trade.
 *
 * <p>The same open-position request retried after a timeout carries the same key, which is what
 * lets a later layer tell "the client asked twice" apart from "the client wants two loans". This
 * type only has to make that comparison possible; deciding what to do about a repeat is the use
 * case's job, and arrives in stage 4.
 */
public record IdempotencyKey(String value) {

  public IdempotencyKey {
    if (value == null || value.isBlank()) {
      throw new MalformedIdentifierException(
          "IdempotencyKey", String.valueOf(value), "must not be blank");
    }
  }
}
