package com.jerome.slledger.application.reconciliation;

import java.math.BigDecimal;

/**
 * The thresholds that decide a break's first disposition.
 *
 * @param absoluteTolerance a value difference up to this many currency units is accepted as
 *     rounding: the two sides round a market value at different points, and a difference of a cent
 *     or so per position is noise that a person should not be asked about
 * @param relativeTolerance or up to this fraction of the ledger's value, whichever is larger, so a
 *     large position is not held to a fixed sum
 * @param settlementWindowDays a ledger-only position is not yet a break until this many days after
 *     it settled: custodians book settlements late
 */
public record ReconPolicy(
    BigDecimal absoluteTolerance, BigDecimal relativeTolerance, int settlementWindowDays) {

  public ReconPolicy {
    if (absoluteTolerance.signum() < 0 || relativeTolerance.signum() < 0) {
      throw new IllegalArgumentException("tolerances must not be negative");
    }
    if (settlementWindowDays < 0) {
      throw new IllegalArgumentException("the settlement window must not be negative");
    }
  }

  public static ReconPolicy defaults() {
    return new ReconPolicy(new BigDecimal("1.00"), new BigDecimal("0.0001"), 2);
  }
}
