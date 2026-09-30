package com.jerome.slledger.application.position;

import com.jerome.slledger.domain.loan.IdempotencyKey;

/**
 * A submission reused an idempotency key that an earlier, different request booked. Answering it
 * with the earlier result would tell the client its new request succeeded.
 */
public class IdempotencyKeyReusedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public IdempotencyKeyReusedException(IdempotencyKey key) {
    super("Idempotency key %s was already used for a different request".formatted(key.value()));
  }
}
