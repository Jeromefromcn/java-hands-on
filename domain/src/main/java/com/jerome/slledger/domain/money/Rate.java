package com.jerome.slledger.domain.money;

import com.jerome.slledger.domain.error.NegativeRateException;
import java.math.BigDecimal;
import java.util.Objects;

/**
 * An annual rate, expressed as a fraction: 0.0365 is 3.65 percent.
 *
 * <p>Deliberately not a {@link Money}, and deliberately not normalised. Money is rounded to its
 * currency's scale on the way in; a rate rounded to two decimal places would turn 3.65 basis points
 * into zero. The two types look similar and must not share a normalisation policy.
 */
public record Rate(BigDecimal annual) {

  public Rate {
    Objects.requireNonNull(annual, "annual");
    if (annual.signum() < 0) {
      throw new NegativeRateException(annual);
    }
  }

  public static Rate of(String annual) {
    return new Rate(new BigDecimal(Objects.requireNonNull(annual, "annual")));
  }

  public Rate plus(Rate other) {
    return new Rate(annual.add(other.annual));
  }
}
