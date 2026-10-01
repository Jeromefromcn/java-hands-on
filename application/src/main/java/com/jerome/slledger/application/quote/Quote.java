package com.jerome.slledger.application.quote;

import com.jerome.slledger.domain.money.Money;
import java.time.Instant;
import java.util.Objects;

/**
 * A price and how fresh it is. A stale quote says when it was true: {@code asOf} is the time the
 * price was last obtained from the source, and a live quote has none.
 */
public record Quote(Money price, boolean stale, Instant asOf) {

  public Quote {
    Objects.requireNonNull(price, "price");
    if (stale != (asOf != null)) {
      throw new IllegalArgumentException("a stale quote has an asOf, a live one does not");
    }
  }

  public static Quote live(Money price) {
    return new Quote(price, false, null);
  }

  public static Quote stale(Money price, Instant asOf) {
    return new Quote(price, true, asOf);
  }
}
