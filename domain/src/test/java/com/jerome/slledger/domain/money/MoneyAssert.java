package com.jerome.slledger.domain.money;

import java.util.Currency;
import org.assertj.core.api.AbstractAssert;

/**
 * A currency-aware AssertJ assertion for {@link Money}, so a comparison reads as one line — {@code
 * assertThat(fee).isEqualTo("12.34 HKD")} — instead of unpacking {@code amount()} and {@code
 * currency()} separately.
 */
public class MoneyAssert extends AbstractAssert<MoneyAssert, Money> {

  MoneyAssert(Money actual) {
    super(actual, MoneyAssert.class);
  }

  /**
   * Parses {@code expected} as {@code "<amount> <currency code>"}, e.g. {@code "12.34 HKD"}, and
   * asserts {@code actual} equals the resulting {@link Money}.
   *
   * @throws IllegalArgumentException if {@code expected} is not exactly two space-separated parts;
   *     this is a malformed test fixture, not a domain concern, so it is not a {@code
   *     DomainException}.
   */
  public MoneyAssert isEqualTo(String expected) {
    isNotNull();
    String[] parts = expected.split(" ");
    if (parts.length != 2) {
      throw new IllegalArgumentException(
          "Expected \"<amount> <currency>\", e.g. \"12.34 HKD\", but got: " + expected);
    }
    Money expectedMoney = Money.of(parts[0], Currency.getInstance(parts[1]));
    if (!actual.equals(expectedMoney)) {
      failWithMessage(
          "Expected money to be <%s> but was <%s>", format(expectedMoney), format(actual));
    }
    return this;
  }

  public MoneyAssert hasCurrency(Currency expected) {
    isNotNull();
    if (!actual.currency().equals(expected)) {
      failWithMessage("Expected currency <%s> but was <%s>", expected, actual.currency());
    }
    return this;
  }

  private static String format(Money money) {
    return money.amount() + " " + money.currency().getCurrencyCode();
  }
}
