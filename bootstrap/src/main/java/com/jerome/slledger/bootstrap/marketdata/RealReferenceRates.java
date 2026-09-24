package com.jerome.slledger.bootstrap.marketdata;

import com.jerome.slledger.bootstrap.timing.Timed;
import com.jerome.slledger.domain.accrual.ReferenceRates;
import com.jerome.slledger.domain.money.Rate;
import java.time.LocalDate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Selected by {@code marketdata.source=real}. A stub: this stage proves the wiring switches, and
 * the HTTP call behind it is stage 7's {@code RestClient} exercise.
 */
@Component
@Timed
@ConditionalOnProperty(name = "marketdata.source", havingValue = "real")
public class RealReferenceRates implements ReferenceRates {

  @Override
  public Rate on(LocalDate date) {
    throw new UnsupportedOperationException(
        "real market data source not implemented until stage 7");
  }
}
