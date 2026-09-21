package com.jerome.slledger.domain.accrual;

import com.jerome.slledger.domain.money.Rate;
import java.time.LocalDate;

/**
 * The benchmark rate in force on a given business date.
 *
 * <p>A named interface rather than a {@code Function<LocalDate, Rate>} field: the name says what
 * the collaborator is, and the day this needs a second method — the date the rate was published,
 * say, which an audit will ask for — it has somewhere to go.
 */
@FunctionalInterface
public interface ReferenceRates {

  Rate on(LocalDate date);
}
