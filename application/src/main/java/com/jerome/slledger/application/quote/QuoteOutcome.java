package com.jerome.slledger.application.quote;

import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.money.Money;

/** One security's result in a batch: a price, or the reason there is none. */
public sealed interface QuoteOutcome {

  Isin isin();

  record Priced(Isin isin, Money price) implements QuoteOutcome {}

  record Failed(Isin isin, Kind kind) implements QuoteOutcome {}

  enum Kind {
    /** The source did not answer, or could not be reached. */
    UNAVAILABLE,
    /** The source answered something this ledger will not accept. */
    REJECTED,
    /** The ledger or the source does not know the security. */
    UNKNOWN,
    /** No answer before the batch's deadline. */
    TIMED_OUT,
    /** The executor refused the work: its queue and threads were full. */
    OVERLOADED,
    /** Anything else; the cause is logged, not returned. */
    ERROR
  }
}
