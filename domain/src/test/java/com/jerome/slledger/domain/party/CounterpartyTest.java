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
}
