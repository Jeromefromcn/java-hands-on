package com.jerome.slledger.bootstrap.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Accrual rule bounds, bound from {@code accrual.*} and validated before any bean uses them: an
 * invalid value refuses startup instead of surfacing on the first accrual run.
 *
 * <p>The bounds carry no absolute range of their own — only their order is a rule — and Bean
 * Validation has no cross-field annotation, so the ordering is an {@link AssertTrue} method.
 */
@Validated
@ConfigurationProperties(prefix = "accrual")
public record AccrualRuleProperties(
    @NotNull BigDecimal rateLowerBound,
    @NotNull BigDecimal rateUpperBound,
    @Min(0) @Max(10) int roundingPrecision) {

  @AssertTrue(message = "rate-lower-bound must be strictly less than rate-upper-bound")
  public boolean isBoundsOrdered() {
    // A missing bound is @NotNull's violation to report, not this one's.
    return rateLowerBound == null
        || rateUpperBound == null
        || rateLowerBound.compareTo(rateUpperBound) < 0;
  }
}
