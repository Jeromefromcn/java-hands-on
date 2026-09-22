package com.jerome.slledger.domain.money;

/**
 * Entry point for {@link MoneyAssert}. Named distinctly from AssertJ's own {@code assertThat} so
 * both can be statically imported in the same test class.
 */
public final class MoneyAssertions {

  private MoneyAssertions() {}

  public static MoneyAssert assertThat(Money actual) {
    return new MoneyAssert(actual);
  }
}
