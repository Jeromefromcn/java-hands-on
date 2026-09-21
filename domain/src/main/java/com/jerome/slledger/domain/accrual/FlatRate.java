package com.jerome.slledger.domain.accrual;

import com.jerome.slledger.domain.money.Rate;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/** One rate for the whole outstanding value, every day. */
public record FlatRate(Rate rate) implements AccrualRule {

  public FlatRate {
    Objects.requireNonNull(rate, "rate");
  }

  @Override
  public BigDecimal dailyFee(BigDecimal outstandingValue, LocalDate on) {
    return outstandingValue.multiply(rate.annual()).divide(DAY_COUNT_BASIS, PRECISION);
  }
}
