package com.jerome.slledger.application.accrual;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jerome.slledger.domain.accrual.AccrualEntry;
import com.jerome.slledger.domain.accrual.FlatRate;
import com.jerome.slledger.domain.error.NegativeOutstandingValueException;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.loan.LoanPosition;
import com.jerome.slledger.domain.loan.LoanPositionId;
import com.jerome.slledger.domain.money.Money;
import com.jerome.slledger.domain.money.Rate;
import com.jerome.slledger.domain.party.CounterpartyId;
import java.time.LocalDate;
import java.util.Currency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AccrueDayTest {

  private static final Currency HKD = Currency.getInstance("HKD");
  private static final Currency JPY = Currency.getInstance("JPY");
  private static final LocalDate DAY = LocalDate.of(2026, 9, 30);

  private static LoanPosition position(long quantity, Currency collateralCurrency) {
    return LoanPosition.open(
        new LoanPositionId("P-1"),
        new CounterpartyId("CP-001"),
        new Isin("HK0000000001"),
        quantity,
        Money.of("1000", collateralCurrency),
        new FlatRate(Rate.of("0.0365")));
  }

  @Test
  @DisplayName("the fee is the outstanding value through the rule, rounded once to the currency")
  void feeIsRoundedOnce() {
    // 1,000 x 41.26 = 41,260.00; x 3.65% / 365 = 4.126 exactly; HALF_UP at scale 2 is 4.13.
    AccrualCandidate candidate =
        new AccrualCandidate.Valid(position(1_000, HKD), Money.of("41.26", HKD));

    AccrualEntry entry = AccrueDay.accrue(candidate, DAY);

    assertThat(entry.position()).isEqualTo(new LoanPositionId("P-1"));
    assertThat(entry.accruedOn()).isEqualTo(DAY);
    assertThat(entry.fee()).isEqualTo(Money.of("4.13", HKD));
  }

  @Test
  @DisplayName("a currency without minor units books whole units")
  void yenIsBookedInWholeUnits() {
    // 1,000 x 1,500 JPY = 1,500,000; x 3.65% / 365 = 150.
    AccrualCandidate candidate =
        new AccrualCandidate.Valid(position(1_000, JPY), Money.of("1500", JPY));

    assertThat(AccrueDay.accrue(candidate, DAY).fee()).isEqualTo(Money.of("150", JPY));
  }

  @Test
  @DisplayName("a part-returned position accrues on what is still outstanding")
  void partReturnedUsesOutstanding() {
    LoanPosition partReturned = position(1_000, HKD);
    partReturned.returnQuantity(600);
    AccrualCandidate candidate = new AccrualCandidate.Valid(partReturned, Money.of("41.26", HKD));

    // 400 x 41.26 = 16,504.00; x 3.65% / 365 = 1.6504; scale 2 is 1.65.
    assertThat(AccrueDay.accrue(candidate, DAY).fee()).isEqualTo(Money.of("1.65", HKD));
  }

  @Test
  @DisplayName("a row that could not be restored is refused as that row's problem")
  void corruptRowIsRefused() {
    AccrualCandidate corrupt = new AccrualCandidate.Corrupt("P-9", "unknown rule type FLOATING");

    assertThatThrownBy(() -> AccrueDay.accrue(corrupt, DAY))
        .isInstanceOfSatisfying(
            CorruptPositionException.class,
            e -> {
              assertThat(e.positionId()).isEqualTo("P-9");
              assertThat(e.getMessage()).contains("unknown rule type FLOATING");
            });
  }

  @Test
  @DisplayName("a negative price is refused by the domain, not booked as a negative fee")
  void negativeValueIsRefused() {
    AccrualCandidate candidate =
        new AccrualCandidate.Valid(position(1_000, HKD), Money.of("-1.00", HKD));

    assertThatThrownBy(() -> AccrueDay.accrue(candidate, DAY))
        .isInstanceOf(NegativeOutstandingValueException.class);
  }
}
