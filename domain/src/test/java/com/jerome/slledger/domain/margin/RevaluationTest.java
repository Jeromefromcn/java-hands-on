package com.jerome.slledger.domain.margin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.jerome.slledger.domain.accrual.AccrualRule;
import com.jerome.slledger.domain.accrual.FlatRate;
import com.jerome.slledger.domain.error.CurrencyMismatchException;
import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.loan.LoanPosition;
import com.jerome.slledger.domain.loan.LoanPositionId;
import com.jerome.slledger.domain.money.Money;
import com.jerome.slledger.domain.money.Rate;
import com.jerome.slledger.domain.party.CounterpartyId;
import java.time.LocalDate;
import java.util.Currency;
import org.junit.jupiter.api.Test;

/**
 * Revaluation: does the collateral still cover what the borrower is holding?
 *
 * <p>Collateral throughout is 4,126,000.00 HKD, which is 10,000 shares at 412.60. The three cases
 * that matter are the price a cent below that, exactly at it, and a cent above.
 */
class RevaluationTest {

  private static final Currency HKD = Currency.getInstance("HKD");
  private static final Currency USD = Currency.getInstance("USD");
  private static final AccrualRule RULE = new FlatRate(Rate.of("0.0365"));
  private static final LocalDate TODAY = LocalDate.of(2026, 3, 2);
  private static final Money COLLATERAL = Money.of("4126000.00", HKD);

  @Test
  void aPriceRiseBeyondTheCollateralProducesACallForExactlyTheShortfall() {
    // 10,000 x 412.61 = 4,126,100.00 against collateral of 4,126,000.00 -> short by 100.00.
    LoanPosition position = aPositionOf(10_000L);

    MarginCall call = position.revalue(Money.of("412.61", HKD), TODAY).orElseThrow();

    assertThat(call.shortfall()).isEqualTo(Money.of("100.00", HKD));
    assertThat(call.position()).isEqualTo(new LoanPositionId("LP-0001"));
    assertThat(call.calledOn()).isEqualTo(TODAY);
  }

  @Test
  void collateralThatExactlyCoversTheMarketValueProducesNoCall() {
    // The boundary. A margin call for zero is not a margin call, and a system that raises one
    // teaches its recipients to ignore them.
    LoanPosition position = aPositionOf(10_000L);

    assertThat(position.revalue(Money.of("412.60", HKD), TODAY)).isEmpty();
  }

  @Test
  void aPriceFallProducesNoCall() {
    LoanPosition position = aPositionOf(10_000L);

    assertThat(position.revalue(Money.of("412.59", HKD), TODAY)).isEmpty();
  }

  @Test
  void onlyTheOutstandingQuantityIsRevaluedNotTheOriginal() {
    // After returning 6,000, the borrower holds 4,000 shares. At 412.61 that is 1,650,440.00,
    // far inside the collateral that was posted for ten thousand.
    LoanPosition position = aPositionOf(10_000L);
    position.returnQuantity(6_000L);

    assertThat(position.revalue(Money.of("412.61", HKD), TODAY)).isEmpty();
  }

  @Test
  void aClosedPositionIsNeverShort() {
    LoanPosition position = aPositionOf(10_000L);
    position.returnQuantity(10_000L);

    assertThat(position.revalue(Money.of("99999.99", HKD), TODAY)).isEmpty();
  }

  @Test
  void aPriceInAnotherCurrencyIsRefusedRatherThanConverted() {
    LoanPosition position = aPositionOf(10_000L);

    assertThatExceptionOfType(CurrencyMismatchException.class)
        .isThrownBy(() -> position.revalue(Money.of("52.90", USD), TODAY));
  }

  @Test
  void aMarginCallForNothingCannotBeConstructed() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> new MarginCall(new LoanPositionId("LP-0001"), TODAY, Money.zero(HKD)));
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(
            () -> new MarginCall(new LoanPositionId("LP-0001"), TODAY, Money.of("-1.00", HKD)));
  }

  private static LoanPosition aPositionOf(long quantity) {
    return LoanPosition.open(
        new LoanPositionId("LP-0001"),
        new CounterpartyId("CP-0001"),
        new Isin("HK0000000001"),
        quantity,
        COLLATERAL,
        RULE);
  }
}
