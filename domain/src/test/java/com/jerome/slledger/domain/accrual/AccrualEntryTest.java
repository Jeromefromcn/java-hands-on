package com.jerome.slledger.domain.accrual;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.jerome.slledger.domain.error.NegativeOutstandingValueException;
import com.jerome.slledger.domain.loan.LoanPositionId;
import com.jerome.slledger.domain.money.Money;
import com.jerome.slledger.domain.money.MoneyAssertions;
import com.jerome.slledger.domain.money.Rate;
import java.time.LocalDate;
import java.util.Currency;
import org.junit.jupiter.api.Test;

/**
 * AccrualEntry.accrue is the only place in this domain where a computed figure becomes a booked
 * amount. These tests are about that one line.
 */
class AccrualEntryTest {

  private static final Currency HKD = Currency.getInstance("HKD");
  private static final LoanPositionId POSITION = new LoanPositionId("LP-0001");
  private static final LocalDate DAY = LocalDate.of(2026, 3, 2);

  @Test
  void anExactDailyFeeIsBookedUnchanged() {
    AccrualEntry entry =
        AccrualEntry.accrue(
            POSITION, Money.of("1000000.00", HKD), new FlatRate(Rate.of("0.0365")), DAY);

    MoneyAssertions.assertThat(entry.fee()).isEqualTo("100.00 HKD");
    assertThat(entry.accruedOn()).isEqualTo(DAY);
    assertThat(entry.position()).isEqualTo(POSITION);
  }

  @Test
  void anInexactDailyFeeIsRoundedHalfUpToTheCurrencyScaleExactlyOnce() {
    // Unrounded: 95.89041095890411. Booked: 95.89.
    AccrualEntry entry =
        AccrualEntry.accrue(
            POSITION, Money.of("1000000.00", HKD), new FlatRate(Rate.of("0.0350")), DAY);

    MoneyAssertions.assertThat(entry.fee()).isEqualTo("95.89 HKD");
  }

  @Test
  void theBookedFeeKeepsTheCurrencyOfTheOutstandingValue() {
    Currency jpy = Currency.getInstance("JPY");

    AccrualEntry entry =
        AccrualEntry.accrue(
            POSITION, Money.of("1000000", jpy), new FlatRate(Rate.of("0.0365")), DAY);

    // JPY has no fraction digits, so the same rate books a whole-yen fee.
    MoneyAssertions.assertThat(entry.fee()).isEqualTo("100 JPY");
    assertThat(entry.fee().amount().scale()).isZero();
  }

  @Test
  void aDailyFeeBelowHalfACentBooksAsZeroAndTheEntryStillExists() {
    // 1.00 x 0.0001 / 365 = 0.000000273972... Booked: 0.00.
    // The entry is still written. An accrual that rounds to nothing is not an accrual that did
    // not happen, and the ledger has to be able to tell those apart.
    AccrualEntry entry =
        AccrualEntry.accrue(POSITION, Money.of("1.00", HKD), new FlatRate(Rate.of("0.0001")), DAY);

    MoneyAssertions.assertThat(entry.fee()).isEqualTo("0.00 HKD");
  }

  @Test
  void aNegativeOutstandingValueIsRefusedRatherThanBookedAsACredit() {
    assertThatExceptionOfType(NegativeOutstandingValueException.class)
        .isThrownBy(
            () ->
                AccrualEntry.accrue(
                    POSITION, Money.of("-1.00", HKD), new FlatRate(Rate.of("0.0365")), DAY));
  }
}
