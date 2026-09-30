package com.jerome.slledger.bootstrap.marketdata;

import com.jerome.slledger.bootstrap.timing.Timed;
import com.jerome.slledger.domain.accrual.ReferenceRates;
import com.jerome.slledger.domain.money.Rate;
import java.time.LocalDate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Selected by {@code marketdata.source=real}. A stub: stage 4 proved the wiring switches. Stage 7's
 * {@code RestClient} exercise built a <em>price</em> source instead ({@code
 * RestMarketPriceSource}): no persisted accrual rule reads a reference rate, because {@code
 * FloatingRate} is not persisted (stage 6). A real reference rate source is not scheduled.
 */
@Component
@Timed
@ConditionalOnProperty(name = "marketdata.source", havingValue = "real")
public class RealReferenceRates implements ReferenceRates {

  @Override
  public Rate on(LocalDate date) {
    throw new UnsupportedOperationException(
        "a real reference rate source is not scheduled; stage 7 built a price source instead");
  }
}
