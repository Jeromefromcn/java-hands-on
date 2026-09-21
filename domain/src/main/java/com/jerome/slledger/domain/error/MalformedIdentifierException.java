package com.jerome.slledger.domain.error;

/**
 * An identifier was offered in a shape its type does not accept.
 *
 * <p>The message names the type, not just the value: by the time a bad identifier surfaces it has
 * usually been copied between three of them, and which one refused it is the part that locates the
 * bug.
 */
public final class MalformedIdentifierException extends DomainException {

  private static final long serialVersionUID = 1L;

  public MalformedIdentifierException(String type, String value, String requirement) {
    super("%s rejected '%s': %s".formatted(type, value, requirement));
  }
}
