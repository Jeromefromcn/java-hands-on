package com.jerome.slledger.domain.accrual;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.domain.loan.LoanPositionId;
import com.jerome.slledger.domain.money.Money;
import com.jerome.slledger.domain.money.Rate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The day count as a value. Expected figures are hand-computed, as in {@link AccrualRuleTest}: the
 * annual fees are chosen to divide exactly by both 365 and 360.
 */
class DayCountTest {

  private static final LocalDate ANY_DAY = LocalDate.of(2026, 3, 2);

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({
    "USD, ACT_360",
    "EUR, ACT_360",
    "CHF, ACT_360",
    "HKD, ACT_365F",
    "GBP, ACT_365F",
    "JPY, ACT_365F"
  })
  void eachCurrencyHasItsMarketConvention(String currency, DayCount expected) {
    assertThat(DayCount.marketConvention(Currency.getInstance(currency))).isEqualTo(expected);
  }

  @Test
  void theSameAnnualFeeIsDividedByEachBasisOnce() {
    // 1,314,000.00 x 0.0200 = 26,280 a year; / 365 = 72 a day; / 360 = 73 a day.
    AccrualRule rule = new FlatRate(Rate.of("0.0200"));
    BigDecimal value = new BigDecimal("1314000.00");

    assertThat(rule.annualFee(value, ANY_DAY)).isEqualByComparingTo("26280");
    assertThat(rule.dailyFee(value, ANY_DAY, DayCount.ACT_365F)).isEqualByComparingTo("72");
    assertThat(rule.dailyFee(value, ANY_DAY, DayCount.ACT_360)).isEqualByComparingTo("73");
  }

  @Test
  void withoutADayCountTheFeeIsTheOneTheLedgerAlwaysBooked() {
    AccrualRule rule = new FlatRate(Rate.of("0.0350"));
    BigDecimal value = new BigDecimal("1000000.00");

    assertThat(rule.dailyFee(value, ANY_DAY))
        .isEqualByComparingTo(rule.dailyFee(value, ANY_DAY, DayCount.ACT_365F))
        .isEqualByComparingTo("95.89041095890411");
  }

  @Test
  void aTieredRuleDividesOnceAfterSummingItsTiers() {
    // Tiers 0 -> 3.60%, 1,000,000 -> 7.20%. On 2,000,000: 36,000 + 72,000 = 108,000 a year;
    // / 360 = 300 a day exactly, / 365 = 295.8904109589041 (one division, sixteen digits).
    TreeMap<BigDecimal, Rate> tiers = new TreeMap<>();
    tiers.put(BigDecimal.ZERO, Rate.of("0.0360"));
    tiers.put(new BigDecimal("1000000"), Rate.of("0.0720"));
    AccrualRule rule = new MarginalTieredRate(tiers);
    BigDecimal value = new BigDecimal("2000000.00");

    assertThat(rule.dailyFee(value, ANY_DAY, DayCount.ACT_360)).isEqualByComparingTo("300");
    assertThat(rule.dailyFee(value, ANY_DAY, DayCount.ACT_365F))
        .isEqualByComparingTo("295.8904109589041");
  }

  @Test
  void theEntryRoundsOnceUnderTheDayCountItIsGiven() {
    // 1,000,000.00 USD x 0.0500 = 50,000 a year; / 360 = 138.8888... -> 138.89; / 365 = 136.99.
    Money value = Money.of("1000000.00", Currency.getInstance("USD"));
    AccrualRule rule = new FlatRate(Rate.of("0.0500"));
    LoanPositionId id = new LoanPositionId("LP-1");

    assertThat(AccrualEntry.accrue(id, value, rule, ANY_DAY, DayCount.ACT_360).fee())
        .isEqualTo(Money.of("138.89", Currency.getInstance("USD")));
    assertThat(AccrualEntry.accrue(id, value, rule, ANY_DAY).fee())
        .isEqualTo(Money.of("136.99", Currency.getInstance("USD")));
  }
}
