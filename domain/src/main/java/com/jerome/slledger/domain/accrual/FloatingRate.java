package com.jerome.slledger.domain.accrual;

import com.jerome.slledger.domain.money.Rate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A benchmark rate that moves daily, plus a fixed spread.
 *
 * <p>The date parameter on {@link AccrualRule#dailyFee} exists for this implementation alone. The
 * other two ignore it, which is the honest cost of the strategy: one implementation's need shapes
 * the interface the others have to satisfy.
 */
public record FloatingRate(ReferenceRates reference, Rate spread) implements AccrualRule {

  public FloatingRate {
    Objects.requireNonNull(reference, "reference");
    Objects.requireNonNull(spread, "spread");
  }

  @Override
  public BigDecimal dailyFee(BigDecimal outstandingValue, LocalDate on) {
    Rate effective = reference.on(on).plus(spread);
    return outstandingValue.multiply(effective.annual()).divide(DAY_COUNT_BASIS, PRECISION);
  }
}
