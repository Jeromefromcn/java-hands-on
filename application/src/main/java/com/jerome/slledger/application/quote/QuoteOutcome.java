package com.jerome.slledger.application.quote;

import com.jerome.slledger.domain.instrument.Isin;
import com.jerome.slledger.domain.money.Money;

/** One security's result in a batch: a price, or the reason there is none. */
public sealed interface QuoteOutcome {

  Isin isin();

  /** {@code asOf} is set exactly when the price is stale: the time it was last true. */
  record Priced(Isin isin, Money price, boolean stale, java.time.Instant asOf)
      implements QuoteOutcome {

    /** A live price. */
    public Priced(Isin isin, Money price) {
      this(isin, price, false, null);
    }
  }

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
