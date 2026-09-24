package com.jerome.slledger.bootstrap.marketdata;

import com.jerome.slledger.bootstrap.timing.Timed;
import com.jerome.slledger.domain.accrual.ReferenceRates;
import com.jerome.slledger.domain.money.Rate;
import java.time.LocalDate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * A fixed, synthetic benchmark rate for every date. The default source: selected when {@code
 * marketdata.source} is {@code mock} or not set at all.
 */
@Component
@Timed
@ConditionalOnProperty(name = "marketdata.source", havingValue = "mock", matchIfMissing = true)
public class MockReferenceRates implements ReferenceRates {

  private static final Rate FIXED = Rate.of("0.0365");

  @Override
  public Rate on(LocalDate date) {
    return FIXED;
  }
}
