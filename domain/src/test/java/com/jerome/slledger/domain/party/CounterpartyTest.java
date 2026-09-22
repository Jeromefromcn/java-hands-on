package com.jerome.slledger.domain.party;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.jerome.slledger.domain.money.Money;
import java.util.Currency;
import org.junit.jupiter.api.Test;

/** All counterparty names in this repository are synthetic. No real institution appears. */
class CounterpartyTest {

  private static final Currency HKD = Currency.getInstance("HKD");

  @Test
  void aCounterpartyCarriesItsOwnLimit() {
    Counterparty borrower =
        new Counterparty(
            new CounterpartyId("CP-0001"),
            "Northpoint Capital",
            new CreditLimit(Money.of("10000000.00", HKD)));

    assertThat(borrower.creditLimit().ceiling()).isEqualTo(Money.of("10000000.00", HKD));
  }

  @Test
  void aCounterpartyNeedsAName() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(
            () ->
                new Counterparty(
                    new CounterpartyId("CP-0001"), "", new CreditLimit(Money.of("1.00", HKD))));
  }

  /**
   * The named counter-example to coverage-as-verification, for {@code
   * docs/decisions/03-testing-foundation.md}. This test walks every line of the canonical
   * constructor's happy path — the exact lines {@code aCounterpartyCarriesItsOwnLimit} above
   * already walks — but checks nothing about the object it built. JaCoCo's line counter cannot tell
   * this test apart from one that verifies something; only reading it can.
   */
  @Test
  void constructionCoversEveryLineOfTheConstructorButAssertsNothingAboutTheResult() {
    new Counterparty(
        new CounterpartyId("CP-0002"), "Anything At All", new CreditLimit(Money.of("1.00", HKD)));
  }
}
