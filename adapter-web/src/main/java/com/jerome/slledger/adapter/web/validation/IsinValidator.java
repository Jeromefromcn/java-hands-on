package com.jerome.slledger.adapter.web.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.util.regex.Pattern;

/**
 * ISO 6166: letters expand to two digits ({@code A} = 10 … {@code Z} = 35), then the Luhn mod-10
 * check runs over the digit string, the check digit included.
 */
public class IsinValidator implements ConstraintValidator<ValidIsin, String> {

  private static final Pattern SHAPE = Pattern.compile("[A-Z]{2}[A-Z0-9]{9}[0-9]");

  @Override
  public boolean isValid(String value, ConstraintValidatorContext context) {
    return value == null || (SHAPE.matcher(value).matches() && luhn(expand(value)));
  }

  /** The check digit that completes an 11-character ISIN prefix. */
  public static char checkDigitFor(String prefix) {
    for (char digit = '0'; digit <= '9'; digit++) {
      if (luhn(expand(prefix + digit))) {
        return digit;
      }
    }
    throw new IllegalStateException("Luhn always has exactly one completing digit");
  }

  private static String expand(String isin) {
    StringBuilder digits = new StringBuilder(24);
    for (char c : isin.toCharArray()) {
      digits.append(Character.digit(c, 36));
    }
    return digits.toString();
  }

  private static boolean luhn(String digits) {
    int sum = 0;
    boolean doubled = false;
    for (int i = digits.length() - 1; i >= 0; i--) {
      int d = digits.charAt(i) - '0';
      if (doubled) {
        d *= 2;
        if (d > 9) {
          d -= 9;
        }
      }
      sum += d;
      doubled = !doubled;
    }
    return sum % 10 == 0;
  }
}
