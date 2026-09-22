package com.jerome.slledger.domain.accrual;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.domain.loan.LoanPositionId;
import com.jerome.slledger.domain.money.Money;
import com.jerome.slledger.domain.money.MoneyAssertions;
import com.jerome.slledger.domain.money.Rate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Currency;
import org.junit.jupiter.api.Test;

/**
 * What double actually costs, and what the per-entry rounding policy actually costs.
 *
 * <p>ROADMAP stage 2 expects the 365-day double accumulation to produce the memorable number. It
 * does not: on one position for one year the drift is about 1.5e-10 HKD and never reaches the
 * booked cent. The two numbers that do matter are elsewhere, and both are reproduced here.
 *
 * <p>Every figure asserted below was measured on this machine. IEEE 754 is deterministic, so these
 * assertions are stable; they are not estimates. See {@code docs/decisions/02-domain-model.md} and
 * {@code docs/evidence/02-precision-drift.txt}.
 */
class AccrualPrecisionTest {

  private static final Currency HKD = Currency.getInstance("HKD");
  private static final LoanPositionId POSITION = new LoanPositionId("LP-0001");
  private static final LocalDate START = LocalDate.of(2026, 1, 1);

  private static final Money ONE_MILLION = Money.of("1000000.00", HKD);
  private static final Rate THREE_AND_A_HALF_PERCENT = Rate.of("0.0350");
  private static final int DAYS = 365;

  @Test
  void reproducesDoubleDrift() {
    // The same year of accrual, twice. The BigDecimal side is this domain's own code.
    Money bigDecimalTotal = accrueForAYearInBigDecimal();
    double doubleTotal = accrueForAYearInDouble();

    // 34,999.85 exactly, reproducible by adding 365 booked entries of 95.89.
    MoneyAssertions.assertThat(bigDecimalTotal).isEqualTo("34999.85 HKD");

    // 34999.8499999998530256561934947967529296875 — the value double actually holds.
    assertThat(doubleTotal).isNotEqualTo(bigDecimalTotal.amount().doubleValue());
    assertThat(new BigDecimal(doubleTotal).subtract(bigDecimalTotal.amount()))
        .isEqualByComparingTo(new BigDecimal("-1.469743438065052032470703125E-10"));

    // And the finding the roadmap did not predict: at this scale the drift does not reach the
    // cent. A ledger that booked the double figure would look correct.
    assertThat(BigDecimal.valueOf(doubleTotal).setScale(2, RoundingMode.HALF_UP))
        .isEqualByComparingTo(bigDecimalTotal.amount());
  }

  @Test
  void reproducesDoubleDriftReachingTheCentAcrossAPortfolio() {
    // 100,000 positions, each with the year's fee of 34,999.85 above.
    BigDecimal exact = BigDecimal.ZERO;
    double drifting = 0;
    for (int position = 0; position < 100_000; position++) {
      exact = exact.add(new BigDecimal("34999.85"));
      drifting += 34999.85;
    }

    assertThat(exact).isEqualByComparingTo(new BigDecimal("3499985000.00"));

    // Six thousandths of a dollar short, which is enough: rounded to cents the portfolio total
    // is 3,499,984,999.99. One cent that no reconciliation will ever account for, because no
    // single entry is wrong.
    assertThat(new BigDecimal(drifting).subtract(exact))
        .isEqualByComparingTo(new BigDecimal("-0.006094455718994140625"));
    assertThat(BigDecimal.valueOf(drifting).setScale(2, RoundingMode.HALF_UP))
        .isEqualByComparingTo(new BigDecimal("3499984999.99"));
  }

  @Test
  void reproducesDoubleRoundingTheWrongWayAtHalfACent() {
    // No accumulation, no loop. One value, one rounding, one cent wrong.
    // double cannot hold 1.005; it holds 1.00499999999999989341858963598497211933135986328125,
    // so Math.round rounds it down.
    assertThat(new BigDecimal("1.005").setScale(2, RoundingMode.HALF_UP))
        .isEqualByComparingTo(new BigDecimal("1.01"));
    assertThat(Math.round(1.005 * 100) / 100.0).isEqualTo(1.00);

    // And this is what makes it undebuggable rather than merely wrong: the neighbouring values
    // behave differently. 2.005 lands just above its decimal value in binary and rounds up.
    assertThat(Math.round(2.005 * 100) / 100.0).isEqualTo(2.01);
    assertThat(Math.round(1.015 * 100) / 100.0).isEqualTo(1.01);
    assertThat(Math.round(1.045 * 100) / 100.0).isEqualTo(1.05);
  }

  @Test
  void perEntryRoundingCostsFifteenCentsAYearAgainstTheExactFigure() {
    // This one is not a defect. It is the price of the policy chosen in the spec: an accrual
    // entry is the smallest auditable unit, so it is rounded when it is booked and never
    // recomputed. The exact year's fee is a single multiplication, 1,000,000 x 0.035.
    BigDecimal exactYear = new BigDecimal("1000000.00").multiply(new BigDecimal("0.0350"));
    Money perEntry = accrueForAYearInBigDecimal();

    assertThat(exactYear).isEqualByComparingTo(new BigDecimal("35000.00"));
    assertThat(exactYear.subtract(perEntry.amount())).isEqualByComparingTo(new BigDecimal("0.15"));

    // Nine orders of magnitude larger than the double drift on the same position, and it is the
    // one that was chosen deliberately.
  }

  private static Money accrueForAYearInBigDecimal() {
    AccrualRule rule = new FlatRate(THREE_AND_A_HALF_PERCENT);
    Money total = Money.zero(HKD);
    for (int day = 0; day < DAYS; day++) {
      total =
          total.plus(AccrualEntry.accrue(POSITION, ONE_MILLION, rule, START.plusDays(day)).fee());
    }
    return total;
  }

  private static double accrueForAYearInDouble() {
    double daily = 1_000_000.00 * 0.0350 / 365;
    double booked = Math.round(daily * 100) / 100.0;
    double total = 0;
    for (int day = 0; day < DAYS; day++) {
      total += booked;
    }
    return total;
  }
}
