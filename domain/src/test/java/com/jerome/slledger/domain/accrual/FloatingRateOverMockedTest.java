package com.jerome.slledger.domain.accrual;

import static com.jerome.slledger.domain.money.MoneyAssertions.assertThat;
import static org.mockito.Mockito.when;

import com.jerome.slledger.domain.loan.LoanPositionId;
import com.jerome.slledger.domain.money.Money;
import com.jerome.slledger.domain.money.Rate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The negative example for {@code docs/decisions/03-testing-foundation.md}: mocking {@link Rate}
 * and {@link Money} in addition to {@link ReferenceRates}, the one real boundary this scenario has.
 * Both are immutable value types this module defines, with no behaviour to fake — mocking them buys
 * nothing and the test still has to {@code when(...)} their trivial accessors before it can run.
 * Kept, not deleted, so the decision record can point at it.
 *
 * <p>{@link ReferenceRates#on} is the one call that genuinely represents an external boundary (a
 * future market-data source); {@link FloatingRateTest} mocks only that.
 */
@ExtendWith(MockitoExtension.class)
class FloatingRateOverMockedTest {

  private static final LoanPositionId POSITION = new LoanPositionId("LP-0001");
  private static final LocalDate DAY = LocalDate.of(2026, 3, 2);

  @Mock private ReferenceRates reference;
  @Mock private Rate referenceRate;
  @Mock private Rate spread;
  @Mock private Rate effectiveRate;
  @Mock private Money outstandingValue;

  @Test
  void aFloatingRateTakesTheReferenceOfTheDayAndAddsItsSpread() {
    // Every one of these stubs exists only because the object it stubs was mocked instead of
    // constructed. None of it is exercising real behaviour.
    when(reference.on(DAY)).thenReturn(referenceRate);
    when(referenceRate.plus(spread)).thenReturn(effectiveRate);
    when(effectiveRate.annual()).thenReturn(new BigDecimal("0.0730"));
    when(outstandingValue.amount()).thenReturn(new BigDecimal("1000000.00"));
    when(outstandingValue.isNegative()).thenReturn(false);
    when(outstandingValue.currency()).thenReturn(Currency.getInstance("HKD"));

    AccrualRule rule = new FloatingRate(reference, spread);
    AccrualEntry entry = AccrualEntry.accrue(POSITION, outstandingValue, rule, DAY);

    // Same outcome as AccrualRuleTest.aFloatingRateTakesTheReferenceOfTheDayAndAddsItsSpread:
    // 1,000,000.00 x 0.0730 / 365 = 200.00 a day.
    assertThat(entry.fee()).isEqualTo("200.00 HKD");
  }
}
