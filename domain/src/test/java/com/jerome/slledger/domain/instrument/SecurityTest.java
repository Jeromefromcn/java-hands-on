package com.jerome.slledger.domain.instrument;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.jerome.slledger.domain.error.CurrencyMismatchException;
import com.jerome.slledger.domain.money.Money;
import java.util.Currency;
import org.junit.jupiter.api.Test;

/** All instrument names in this repository are synthetic. No real issuer appears anywhere. */
class SecurityTest {

  private static final Currency HKD = Currency.getInstance("HKD");
  private static final Currency USD = Currency.getInstance("USD");
  private static final Isin ANY_ISIN = new Isin("HK0000000001");

  @Test
  void aSecurityCarriesItsPriceAsMoney() {
    Security security = new Security(ANY_ISIN, "Harbour Holdings", HKD, Money.of("412.60", HKD));

    assertThat(security.marketPrice()).isEqualTo(Money.of("412.60", HKD));
    assertThat(security.currency()).isEqualTo(HKD);
  }

  @Test
  void aPriceInADifferentCurrencyFromTheSecurityIsRefused() {
    // The first type in this domain that could have broken the money invariant quietly: a
    // security listed in HKD holding a price stamped USD reconciles against nothing, and
    // nothing downstream would have noticed.
    assertThatExceptionOfType(CurrencyMismatchException.class)
        .isThrownBy(() -> new Security(ANY_ISIN, "Harbour Holdings", HKD, Money.of("52.90", USD)))
        .withMessageContaining("HKD")
        .withMessageContaining("USD");
  }

  @Test
  void aSecurityNeedsAName() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> new Security(ANY_ISIN, "  ", HKD, Money.of("1.00", HKD)));
  }

  @Test
  void aNegativePriceIsRefused() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> new Security(ANY_ISIN, "Harbour Holdings", HKD, Money.of("-1.00", HKD)));
  }
}
