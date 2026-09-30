package com.jerome.slledger.application.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jerome.slledger.domain.money.Money;
import java.util.Currency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The custodian's file is a money boundary the ledger does not control. A line either becomes a
 * holding with its exact amount and its currency, or it is refused and the reason names why.
 */
class CustodianHoldingTest {

  @Test
  @DisplayName("a valid line keeps its digits and its currency")
  void validLine() {
    CustodianHolding holding = CustodianHolding.parse(2, "P-1,1000,41260.00,HKD");

    assertThat(holding.positionId()).isEqualTo("P-1");
    assertThat(holding.quantity()).isEqualTo(1_000);
    assertThat(holding.value()).isEqualTo(Money.of("41260.00", Currency.getInstance("HKD")));
  }

  @ParameterizedTest(name = "{0} {1}")
  @CsvSource({
    "HKD, 41260.00",
    "HKD, 41260",
    "JPY, 1500000",
    "BHD, 41.260",
    "USD, 0",
    "HKD, 9999999999999.99"
  })
  @DisplayName("an amount within its currency's decimals is accepted, whatever its scale")
  void amountsWithinTheCurrency(String currency, String amount) {
    CustodianHolding holding = CustodianHolding.parse(2, "P-1,1," + amount + "," + currency);

    assertThat(holding.value().currency()).isEqualTo(Currency.getInstance(currency));
    assertThat(holding.value().amount()).isEqualByComparingTo(amount);
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      value = {
        "P-1,1000,41260.005,HKD|3 decimals|HKD allows 2",
        "P-1,1000,1500.5,JPY|a yen with decimals|JPY allows 0",
        "P-1,1000,41260.00,|no currency|the currency is missing",
        "P-1,1000,41260.00,XXY|an unknown currency|unknown currency 'XXY'",
        "P-1,1000,41260.00,XXX|the no-currency code|not a currency amounts are held in",
        "P-1,abc,41260.00,HKD|a non-numeric quantity|not a whole number",
        "P-1,0,41260.00,HKD|a zero quantity|must be positive",
        "P-1,-5,41260.00,HKD|a negative quantity|must be positive",
        "P-1,1000,forty,HKD|a non-numeric value|is not a number",
        "P-1,1000,-1.00,HKD|a negative value|must not be negative",
        "P-1,1000,41260.00|a missing field|expected 4 fields, found 3",
        "P-1,1000,41260.00,HKD,extra|an extra field|expected 4 fields, found 5",
        ",1000,41260.00,HKD|no position id|the position id is empty"
      })
  @DisplayName("a line that breaks the money rule or the format is refused, with the reason")
  void refusedLines(String line, String why, String reason) {
    assertThatThrownBy(() -> CustodianHolding.parse(7, line))
        .as(why)
        .isInstanceOfSatisfying(
            MalformedHoldingException.class,
            e -> {
              assertThat(e.line()).isEqualTo(7);
              assertThat(e.getMessage()).contains(reason);
            });
  }

  @Test
  @DisplayName("a refused line is filed under its position when it names one, else under its line")
  void skipKey() {
    assertThatThrownBy(() -> CustodianHolding.parse(4, "P-9,x,1.00,HKD"))
        .isInstanceOfSatisfying(
            MalformedHoldingException.class, e -> assertThat(e.itemKey()).isEqualTo("P-9"));
    assertThatThrownBy(() -> CustodianHolding.parse(5, ",1,1.00,HKD"))
        .isInstanceOfSatisfying(
            MalformedHoldingException.class, e -> assertThat(e.itemKey()).isEqualTo("line:5"));
  }
}
