package com.jerome.slledger.domain.instrument;

import com.jerome.slledger.domain.error.MalformedIdentifierException;
import java.util.regex.Pattern;

/**
 * An International Securities Identification Number: two-letter country prefix, nine alphanumeric
 * characters, one check digit.
 *
 * <p>The shape is validated; the check digit is not. Validating it would mean every synthetic ISIN
 * in this repository has to carry a correct mod-10 digit, which buys nothing here and makes test
 * fixtures a puzzle. The consequence is stated rather than hidden: an Isin in this domain is
 * well-formed, not verified. See {@code docs/decisions/02-domain-model.md}.
 */
public record Isin(String value) {

  private static final Pattern SHAPE = Pattern.compile("[A-Z]{2}[A-Z0-9]{9}[0-9]");

  public Isin {
    if (value == null || !SHAPE.matcher(value).matches()) {
      throw new MalformedIdentifierException(
          "Isin",
          String.valueOf(value),
          "must be two uppercase letters, nine uppercase alphanumerics and a check digit");
    }
  }
}
