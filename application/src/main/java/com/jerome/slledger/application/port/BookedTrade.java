package com.jerome.slledger.application.port;

import com.jerome.slledger.domain.loan.LoanTrade;
import java.util.Objects;

/**
 * A trade as the ledger holds it, with the fingerprint of the request that booked it.
 *
 * @param fingerprint {@code null} for a trade booked before fingerprints were stored (V4)
 */
public record BookedTrade(LoanTrade trade, RequestFingerprint fingerprint) {

  public BookedTrade {
    Objects.requireNonNull(trade, "trade");
  }

  /**
   * Whether {@code request} is the request that booked this trade. A trade with no stored
   * fingerprint matches nothing: a replay against it would be a guess.
   */
  public boolean bookedBy(RequestFingerprint request) {
    return fingerprint != null && fingerprint.equals(request);
  }
}
