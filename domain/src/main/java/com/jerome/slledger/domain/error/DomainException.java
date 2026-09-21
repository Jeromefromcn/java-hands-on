package com.jerome.slledger.domain.error;

/**
 * The root of every failure this domain raises on its own behalf.
 *
 * <p>Unchecked, and deliberately so. A checked exception earns its keep when the caller has a
 * second path available at the call site — retry the other endpoint, fall back to the cache. No
 * caller of this domain has one: a credit limit is exceeded or it is not, and there is nothing the
 * immediate caller can do about it except stop. Making these checked would only oblige every
 * intervening layer to declare or wrap them, which is how a stack trace turns into six nested
 * causes that say the same thing.
 *
 * <p>Abstract, so that nobody throws the category instead of the case.
 *
 * <p>See {@code docs/decisions/02-domain-model.md}.
 */
public abstract class DomainException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  protected DomainException(String message) {
    super(message);
  }
}
