package com.jerome.slledger.application.port;

import com.jerome.slledger.domain.loan.IdempotencyKey;

/**
 * A submission whose idempotency key has already been booked.
 *
 * <p>Refused, not answered. Returning the original submission's result instead is the web layer's
 * job in stage 7; here it is enough that the second submission changes nothing.
 */
public class DuplicateRequestException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public DuplicateRequestException(IdempotencyKey key, Throwable cause) {
    super("A submission with idempotency key %s was already booked".formatted(key.value()), cause);
  }
}
