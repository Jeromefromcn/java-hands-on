package com.jerome.slledger.domain.accrual;

import static com.jerome.slledger.domain.money.MoneyAssertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jerome.slledger.domain.loan.LoanPositionId;
import com.jerome.slledger.domain.money.Money;
import com.jerome.slledger.domain.money.Rate;
import java.time.LocalDate;
import java.util.Currency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The positive counterpart to {@link FloatingRateOverMockedTest}: mocks only {@link
 * ReferenceRates}, the one collaborator that represents a boundary this module doesn't control.
 * {@link Rate} and {@link Money} are constructed for real. Same outcome, less setup — and this test
 * can additionally verify the interaction, which a hand-written lambda stub (as in {@code
 * AccrualRuleTest.aFloatingRateTakesTheReferenceOfTheDayAndAddsItsSpread}) cannot, because a lambda
 * isn't spied on.
 */
@ExtendWith(MockitoExtension.class)
class FloatingRateTest {

  private static final Currency HKD = Currency.getInstance("HKD");
  private static final LoanPositionId POSITION = new LoanPositionId("LP-0001");
  private static final LocalDate DAY = LocalDate.of(2026, 3, 2);

  @Mock private ReferenceRates reference;

  @Test
  void aFloatingRateTakesTheReferenceOfTheDayAndAddsItsSpread() {
    when(reference.on(DAY)).thenReturn(Rate.of("0.0365"));

    AccrualRule rule = new FloatingRate(reference, Rate.of("0.0365"));
    AccrualEntry entry = AccrualEntry.accrue(POSITION, Money.of("1000000.00", HKD), rule, DAY);

    // 1,000,000.00 x 0.0730 / 365 = 200.00 a day.
    assertThat(entry.fee()).isEqualTo("200.00 HKD");
    verify(reference).on(DAY);
  }
}
