package com.jerome.slledger.application.position;

import com.jerome.slledger.domain.loan.LoanTrade;
import java.util.Objects;

/**
 * What a submission booked, and whether this call booked it or found it already booked.
 *
 * @param trade the trade booked for the submission's key: the whole result, both times
 * @param replayed {@code true} when an earlier submission with the same key and the same request
 *     booked it
 */
public record Outcome(LoanTrade trade, boolean replayed) {

  public Outcome {
    Objects.requireNonNull(trade, "trade");
  }
}
