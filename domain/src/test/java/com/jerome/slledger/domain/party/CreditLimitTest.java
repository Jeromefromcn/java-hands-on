package com.jerome.slledger.domain.party;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.jerome.slledger.domain.error.CurrencyMismatchException;
import com.jerome.slledger.domain.error.InsufficientCreditException;
import com.jerome.slledger.domain.money.Money;
import java.util.Currency;
import org.junit.jupiter.api.Test;

/**
 * The credit limit boundary. The two tests that matter are the ones a cent apart: a limit that is
 * wrong by one cent is wrong in exactly the way nobody notices until it matters.
 */
class CreditLimitTest {

  private static final Currency HKD = Currency.getInstance("HKD");
  private static final Currency USD = Currency.getInstance("USD");
  private static final CreditLimit TEN_MILLION = new CreditLimit(Money.of("10000000.00", HKD));

  @Test
  void anExposureExactlyAtTheCeilingIsAllowed() {
    assertThatCode(
            () ->
                TEN_MILLION.checkCanAbsorb(
                    Money.of("9000000.00", HKD), Money.of("1000000.00", HKD)))
        .doesNotThrowAnyException();
  }

  @Test
  void oneCentAboveTheCeilingIsRefused() {
    assertThatExceptionOfType(InsufficientCreditException.class)
        .isThrownBy(
            () ->
                TEN_MILLION.checkCanAbsorb(
                    Money.of("9000000.00", HKD), Money.of("1000000.01", HKD)))
        .withMessageContaining("10000000.00")
        .withMessageContaining("9000000.00")
        .withMessageContaining("1000000.01");
  }

  @Test
  void anExposureInAnotherCurrencyIsRefusedRatherThanConverted() {
    // There is no FX in this domain. A limit in HKD cannot absorb an exposure in USD, and
    // guessing a rate here would put an unauditable number inside a credit check.
    assertThatExceptionOfType(CurrencyMismatchException.class)
        .isThrownBy(() -> TEN_MILLION.checkCanAbsorb(Money.zero(HKD), Money.of("1000000.00", USD)));
  }

  @Test
  void aNegativeCeilingIsRefused() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> new CreditLimit(Money.of("-1.00", HKD)));
  }
}
