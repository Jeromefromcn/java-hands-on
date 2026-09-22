package com.jerome.slledger.domain.money;

import static com.jerome.slledger.domain.money.MoneyAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.Currency;
import org.junit.jupiter.api.Test;

/**
 * The assertion helper's own tests. An assertion class with no test of its own is exactly the kind
 * of test-support code that can silently stop asserting anything.
 */
class MoneyAssertTest {

  private static final Currency HKD = Currency.getInstance("HKD");
  private static final Currency USD = Currency.getInstance("USD");

  @Test
  void isEqualToPassesWhenAmountAndCurrencyMatch() {
    assertThat(Money.of("12.34", HKD)).isEqualTo("12.34 HKD");
  }

  @Test
  void isEqualToFailsWithAReadableMessageWhenAmountsDiffer() {
    assertThatExceptionOfType(AssertionError.class)
        .isThrownBy(() -> assertThat(Money.of("12.34", HKD)).isEqualTo("99.99 HKD"))
        .withMessageContaining("12.34 HKD")
        .withMessageContaining("99.99 HKD");
  }

  @Test
  void isEqualToFailsWithAReadableMessageWhenCurrenciesDiffer() {
    assertThatExceptionOfType(AssertionError.class)
        .isThrownBy(() -> assertThat(Money.of("12.34", HKD)).isEqualTo("12.34 USD"))
        .withMessageContaining("HKD")
        .withMessageContaining("USD");
  }

  @Test
  void hasCurrencyPassesAndFailsCorrectly() {
    assertThat(Money.of("12.34", HKD)).hasCurrency(HKD);
    assertThatExceptionOfType(AssertionError.class)
        .isThrownBy(() -> assertThat(Money.of("12.34", HKD)).hasCurrency(USD));
  }
}
