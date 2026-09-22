package com.jerome.slledger.domain.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.jerome.slledger.domain.error.CurrencyMismatchException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Arithmetic on Money. Two properties are under test throughout: an operation across two currencies
 * is refused rather than coerced, and no operation picks a rounding mode on the caller's behalf.
 */
@DisplayName("Money arithmetic")
class MoneyArithmeticTest {

  private static final Currency HKD = Currency.getInstance("HKD");
  private static final Currency USD = Currency.getInstance("USD");

  @Nested
  @DisplayName("addition")
  class Addition {

    @Test
    @DisplayName("amounts in the same currency add")
    void amountsInTheSameCurrencyAdd() {
      assertThat(Money.of("10.25", HKD).plus(Money.of("0.75", HKD)))
          .isEqualTo(Money.of("11.00", HKD));
    }

    @Test
    @DisplayName("adding across currencies is refused and the message names both")
    void addingAcrossCurrenciesIsRefusedAndTheMessageNamesBoth() {
      assertThatExceptionOfType(CurrencyMismatchException.class)
          .isThrownBy(() -> Money.of("10.00", HKD).plus(Money.of("10.00", USD)))
          .withMessageContaining("HKD")
          .withMessageContaining("USD");
    }
  }

  @Nested
  @DisplayName("subtraction")
  class Subtraction {

    @Test
    @DisplayName("amounts in the same currency subtract and may go negative")
    void amountsInTheSameCurrencySubtractAndMayGoNegative() {
      Money owed = Money.of("10.00", HKD).minus(Money.of("12.50", HKD));

      assertThat(owed).isEqualTo(Money.of("-2.50", HKD));
      assertThat(owed.isNegative()).isTrue();
      assertThat(Money.zero(HKD).isNegative()).isFalse();
    }

    @Test
    @DisplayName("subtracting across currencies is refused")
    void subtractingAcrossCurrenciesIsRefused() {
      assertThatExceptionOfType(CurrencyMismatchException.class)
          .isThrownBy(() -> Money.of("10.00", HKD).minus(Money.of("1.00", USD)));
    }
  }

  @Nested
  @DisplayName("comparison")
  class Comparison {

    @Test
    @DisplayName("comparing across currencies is refused rather than answered wrongly")
    void comparingAcrossCurrenciesIsRefusedRatherThanAnsweredWrongly() {
      assertThatExceptionOfType(CurrencyMismatchException.class)
          .isThrownBy(() -> Money.of("10.00", HKD).isGreaterThan(Money.of("1.00", USD)));
    }

    @Test
    @DisplayName("comparison is by value, not by scale")
    void comparisonIsByValueNotByScale() {
      assertThat(Money.of("10.01", HKD).isGreaterThan(Money.of("10.00", HKD))).isTrue();
      assertThat(Money.of("10.00", HKD).isGreaterThan(Money.of("10.0", HKD))).isFalse();
    }
  }

  @Nested
  @DisplayName("multiplication")
  class Multiplication {

    @Test
    @DisplayName("the rounding mode passed to multiply is load-bearing")
    void theRoundingModePassedToMultiplyIsLoadBearing() {
      // Half a cent, which is exactly where the two modes disagree. If this test ever passes
      // with both assertions equal, multiply has started deciding the rounding for its caller.
      Money halfACent = Money.of("1.00", HKD);
      BigDecimal factor = new BigDecimal("0.005");

      assertThat(halfACent.multiply(factor, RoundingMode.HALF_UP)).isEqualTo(Money.of("0.01", HKD));
      assertThat(halfACent.multiply(factor, RoundingMode.DOWN)).isEqualTo(Money.of("0.00", HKD));
    }

    @Test
    @DisplayName("multiplication keeps the currency")
    void multiplicationKeepsTheCurrency() {
      assertThat(Money.of("100.00", HKD).multiply(new BigDecimal("0.035"), RoundingMode.HALF_UP))
          .isEqualTo(Money.of("3.50", HKD));
    }
  }
}
