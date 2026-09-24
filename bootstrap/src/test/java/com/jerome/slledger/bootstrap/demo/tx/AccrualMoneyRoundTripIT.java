package com.jerome.slledger.bootstrap.demo.tx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jerome.slledger.bootstrap.demo.tx.support.AccrualEntryRows;
import com.jerome.slledger.bootstrap.demo.tx.support.TxDemo;
import com.jerome.slledger.domain.accrual.AccrualEntry;
import com.jerome.slledger.domain.accrual.FlatRate;
import com.jerome.slledger.domain.loan.LoanPositionId;
import com.jerome.slledger.domain.money.Money;
import com.jerome.slledger.domain.money.Rate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The money invariant at the first column it crosses into: {@code amount numeric(19,6)} plus {@code
 * currency char(3)}. Covers currencies with 2, 0 and 3 minor-unit digits.
 */
class AccrualMoneyRoundTripIT {

  private static final LoanPositionId POSITION = new LoanPositionId("P1");
  private static final LocalDate ON = LocalDate.of(2026, 3, 16);

  @ParameterizedTest(name = "{0}: {1} outstanding accrues {2}, and reads back unchanged")
  @CsvSource({"HKD, 1000000.00, 143.84", "JPY, 1000000, 144", "BHD, 1234.567, 0.178"})
  @DisplayName("an accrual entry reads back equal to the one written: amount, scale and currency")
  void roundTrip(String currencyCode, String outstanding, String expectedFee) {
    Currency currency = Currency.getInstance(currencyCode);
    AccrualEntry written =
        AccrualEntry.accrue(
            POSITION, Money.of(outstanding, currency), new FlatRate(Rate.of("0.0525")), ON);
    // Pins the data, not the domain: the fee really uses every minor digit of its currency, so a
    // column that dropped one (BHD's third) would change the value, not just the scale.
    assertThat(written.fee().amount()).isEqualTo(new BigDecimal(expectedFee));

    try (var context = TxDemo.start(AccrualEntryRows.class)) {
      AccrualEntryRows rows = context.getBean(AccrualEntryRows.class);

      rows.insert(written);

      // Record equality: BigDecimal.equals compares scale, so 143.84 and 143.840000 differ here.
      assertThat(rows.find(POSITION, ON)).contains(written);

      // The column pads every amount to 6 decimal places, whatever the currency. The equality
      // above holds only because Money's constructor re-scales to the currency's minor units on
      // the way back in. The column type does not carry the invariant; Money does.
      assertThat(rows.rawAmount(POSITION, ON).scale()).isEqualTo(6);
    }
  }

  @Test
  @DisplayName(
      "an amount with more digits than its currency allows is refused on read, not rounded")
  void overPreciseAmountIsRefusedOnRead() {
    try (var context = TxDemo.start(AccrualEntryRows.class)) {
      // Written by anything other than AccrualEntryRows: the column accepts 6 decimal places,
      // HKD has 2. Rounding it on read would be a second, silent rounding step outside domain.
      context
          .getBean(JdbcClient.class)
          .sql(
              "INSERT INTO accrual_entry(position_id, business_date, amount, currency)"
                  + " VALUES ('P1', ?, 143.845, 'HKD')")
          .param(ON)
          .update();

      assertThatThrownBy(() -> context.getBean(AccrualEntryRows.class).find(POSITION, ON))
          .isInstanceOf(ArithmeticException.class);
    }
  }
}
