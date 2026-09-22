package com.jerome.slledger.domain.accrual;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.jerome.slledger.domain.money.Rate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The three accrual strategies, on unrounded figures.
 *
 * <p>Every expected value here is hand-computed and the arithmetic is shown. The rates are chosen
 * so that the annual fee is a whole multiple of 365 and the daily figure is exact: a test whose
 * expected value came from running the implementation once proves only that the implementation has
 * not changed.
 */
class AccrualRuleTest {

  private static final LocalDate ANY_DAY = LocalDate.of(2026, 3, 2);
  private static final BigDecimal ONE_MILLION = new BigDecimal("1000000.00");

  @Test
  void aFlatRateChargesTheWholeValueAtOneRate() {
    // 1,000,000.00 x 0.0365 = 36,500 a year; 36,500 / 365 = 100 a day, exactly.
    AccrualRule rule = new FlatRate(Rate.of("0.0365"));

    assertThat(rule.dailyFee(ONE_MILLION, ANY_DAY)).isEqualByComparingTo(new BigDecimal("100"));
  }

  @Test
  void aFlatRateOnARateThatDoesNotDivideEvenlyKeepsItsFullPrecision() {
    // 1,000,000.00 x 0.0350 = 35,000 a year; 35,000 / 365 = 95.890410958904109589...
    // DECIMAL64 keeps 16 significant digits, which is far more than any currency will book.
    AccrualRule rule = new FlatRate(Rate.of("0.0350"));

    assertThat(rule.dailyFee(ONE_MILLION, ANY_DAY))
        .isEqualByComparingTo(new BigDecimal("95.89041095890411"));
  }

  @ParameterizedTest(name = "{0} outstanding -> daily fee {1}")
  @CsvSource({
    // Tiers: 0 -> 3.65%, 1,000,000 -> 7.30%. On 2,000,000:
    //   first  1,000,000 x 0.0365 =  36,500
    //   second 1,000,000 x 0.0730 =  73,000
    //                     annual  = 109,500 ; 109,500 / 365 = 300 a day, exactly.
    // A cliff implementation would charge 2,000,000 x 0.0730 = 146,000, or 400 a day.
    "2000000.00, 300",
    // Exactly 1,000,000 is entirely in the first tier: 36,500 / 365 = 100.
    "1000000.00, 100"
  })
  void aTieredRateChargesEachSliceAtItsOwnRateNotTheWholeValueAtTheTopRate(
      BigDecimal outstandingValue, BigDecimal expectedDailyFee) {
    assertThat(twoTierRule().dailyFee(outstandingValue, ANY_DAY))
        .isEqualByComparingTo(expectedDailyFee);
  }

  @Test
  void oneCentAboveTheThresholdIsChargedOnThatOneCentOnly() {
    // 1,000,000.01 -> 1,000,000 x 0.0365 + 0.01 x 0.0730 = 36,500.00073
    //                 36,500.00073 / 365 = 100.000002, exactly.
    BigDecimal daily = twoTierRule().dailyFee(new BigDecimal("1000000.01"), ANY_DAY);

    assertThat(daily).isEqualByComparingTo(new BigDecimal("100.000002"));
    assertThat(daily).isGreaterThan(twoTierRule().dailyFee(ONE_MILLION, ANY_DAY));
  }

  @Test
  void aTieredRateMustStartAtZeroOrSomeValuesWouldBeUnpriced() {
    TreeMap<BigDecimal, Rate> gapAtTheBottom = new TreeMap<>();
    gapAtTheBottom.put(new BigDecimal("1000000"), Rate.of("0.0365"));

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> new MarginalTieredRate(gapAtTheBottom));
  }

  @Test
  void aFloatingRateTakesTheReferenceOfTheDayAndAddsItsSpread() {
    // 2026-03-02: reference 3.65% + spread 3.65% = 7.30% -> 73,000 / 365 = 200 a day.
    // 2026-03-03: reference 0.00% + spread 3.65% = 3.65% -> 36,500 / 365 = 100 a day.
    LocalDate secondDay = LocalDate.of(2026, 3, 3);
    ReferenceRates reference = day -> day.equals(ANY_DAY) ? Rate.of("0.0365") : Rate.of("0.0000");
    AccrualRule rule = new FloatingRate(reference, Rate.of("0.0365"));

    assertThat(rule.dailyFee(ONE_MILLION, ANY_DAY)).isEqualByComparingTo(new BigDecimal("200"));
    assertThat(rule.dailyFee(ONE_MILLION, secondDay)).isEqualByComparingTo(new BigDecimal("100"));
  }

  @Test
  void twoRateCardsThatDifferOnlyInTrailingZeroesAreTheSameRule() {
    // TreeMap compares BigDecimal keys with compareTo, so 1000000 and 1000000.00 are the same
    // key and the two maps are equal. BigDecimal.hashCode is scale-sensitive, so without
    // canonicalising the keys the record's generated hashCode disagrees with its own equals,
    // and a rule put in a HashSet cannot be found again. The same contract this stage
    // reproduces breaking in IdentityContractTest.
    TreeMap<BigDecimal, Rate> plain = new TreeMap<>();
    plain.put(BigDecimal.ZERO, Rate.of("0.0365"));
    plain.put(new BigDecimal("1000000"), Rate.of("0.0730"));

    TreeMap<BigDecimal, Rate> withTrailingZeroes = new TreeMap<>();
    withTrailingZeroes.put(new BigDecimal("0.00"), Rate.of("0.0365"));
    withTrailingZeroes.put(new BigDecimal("1000000.00"), Rate.of("0.0730"));

    MarginalTieredRate one = new MarginalTieredRate(plain);
    MarginalTieredRate other = new MarginalTieredRate(withTrailingZeroes);

    assertThat(one).isEqualTo(other);
    assertThat(one.hashCode()).isEqualTo(other.hashCode());
    assertThat(new HashSet<>(Set.of(one))).contains(other);
    assertThat(one.dailyFee(new BigDecimal("2000000.00"), ANY_DAY))
        .isEqualByComparingTo(other.dailyFee(new BigDecimal("2000000.00"), ANY_DAY));
  }

  private static MarginalTieredRate twoTierRule() {
    TreeMap<BigDecimal, Rate> tiers = new TreeMap<>();
    tiers.put(BigDecimal.ZERO, Rate.of("0.0365"));
    tiers.put(new BigDecimal("1000000"), Rate.of("0.0730"));
    return new MarginalTieredRate(tiers);
  }
}
