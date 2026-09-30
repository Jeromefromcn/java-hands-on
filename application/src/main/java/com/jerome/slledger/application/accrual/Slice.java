package com.jerome.slledger.application.accrual;

import java.util.Optional;

/**
 * A part of the keyspace one reader is responsible for: optionally one counterparty, and optionally
 * an inclusive id range. Slices from one {@link AccrualCandidates#slices} call do not overlap and
 * together cover every active position.
 */
public record Slice(Optional<String> counterparty, Optional<String> idFrom, Optional<String> idTo) {

  public static final Slice ALL = new Slice(Optional.empty(), Optional.empty(), Optional.empty());

  public static Slice ofCounterparty(String counterparty) {
    return new Slice(Optional.of(counterparty), Optional.empty(), Optional.empty());
  }

  public static Slice ofIds(String from, String to) {
    return new Slice(Optional.empty(), Optional.of(from), Optional.of(to));
  }
}
