package com.jerome.slledger.domain.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.jerome.slledger.domain.error.NegativeRateException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class RateTest {

  @Test
  void aRateKeepsEveryDigitItWasGiven() {
    // Unlike Money, a Rate is not normalised to two decimal places. 3.65 basis points is a
    // real rate; rounding it to 0.00 would be the same class of bug Money exists to prevent,
    // in the opposite direction.
    assertThat(Rate.of("0.000365").annual()).isEqualTo(new BigDecimal("0.000365"));
  }

  @Test
  void aNegativeRateIsRefused() {
    assertThatExceptionOfType(NegativeRateException.class).isThrownBy(() -> Rate.of("-0.01"));
  }

  @Test
  void aZeroRateIsAllowed() {
    assertThat(Rate.of("0").annual()).isEqualByComparingTo(BigDecimal.ZERO);
  }
}
