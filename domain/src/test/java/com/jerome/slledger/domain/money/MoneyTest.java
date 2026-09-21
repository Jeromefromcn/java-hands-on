package com.jerome.slledger.domain.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.Test;

/**
 * Money is always at its currency's scale. Every test here exists because the obvious
 * implementation — a record wrapping a BigDecimal, unnormalised — gets it wrong.
 */
class MoneyTest {

  private static final Currency HKD = Currency.getInstance("HKD");
  private static final Currency JPY = Currency.getInstance("JPY");

  @Test
  void twoAmountsThatDifferOnlyInScaleAreTheSameMoney() {
    assertThat(Money.of("10.00", HKD)).isEqualTo(Money.of("10.0", HKD));
  }

  @Test
  void theUnderlyingBigDecimalsWouldNotHaveBeenEqual() {
    // Why the normalisation exists: BigDecimal.equals compares scale as well as value,
    // so a record wrapping these two unnormalised would report two different amounts
    // of money. This is the single most common way a money type is wrong.
    assertThat(new BigDecimal("10.00")).isNotEqualTo(new BigDecimal("10.0"));
    assertThat(new BigDecimal("10.00")).isEqualByComparingTo(new BigDecimal("10.0"));
  }

  @Test
  void amountsAreRoundedHalfUpToTheCurrencysFractionDigits() {
    assertThat(Money.of("1.005", HKD).amount()).isEqualTo(new BigDecimal("1.01"));
    assertThat(Money.of("1.004", HKD).amount()).isEqualTo(new BigDecimal("1.00"));
  }

  @Test
  void aCurrencyWithNoFractionDigitsIsHeldAtScaleZero() {
    assertThat(Money.of("1234.5", JPY).amount()).isEqualTo(new BigDecimal("1235"));
  }

  @Test
  void anAmountShorterThanTheCurrencysScaleIsPaddedNotLeftRagged() {
    assertThat(Money.of("7", HKD).amount()).isEqualTo(new BigDecimal("7.00"));
  }

  @Test
  void zeroCarriesItsCurrency() {
    assertThat(Money.zero(HKD)).isEqualTo(Money.of("0.00", HKD));
    assertThat(Money.zero(HKD)).isNotEqualTo(Money.zero(JPY));
  }

  @Test
  void aMoneyWithoutAnAmountOrACurrencyCannotBeConstructed() {
    assertThatNullPointerException().isThrownBy(() -> Money.of((BigDecimal) null, HKD));
    assertThatNullPointerException().isThrownBy(() -> Money.of("1.00", null));
  }
}
