package com.jerome.slledger.adapter.web.validation;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * The range an accrual rate sent over HTTP must fall in, both ends inclusive. Supplied as a bean by
 * whoever owns the configuration: {@code bootstrap}, from the same {@code accrual.*} properties
 * that govern the rest of the ledger, so the validator and the configuration cannot disagree.
 */
public record RateBounds(BigDecimal lower, BigDecimal upper) {

  public RateBounds {
    Objects.requireNonNull(lower, "lower");
    Objects.requireNonNull(upper, "upper");
    if (lower.compareTo(upper) > 0) {
      throw new IllegalArgumentException("lower %s is above upper %s".formatted(lower, upper));
    }
  }
}
