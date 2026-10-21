package com.jerome.slledger.application.rollout;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * A proposed raise of a rollout, and what became of it. Raising needs two people: the maker who
 * proposes it and a different checker who approves it. Lowering is not a change of this kind; it
 * takes effect when it is asked for.
 *
 * @param fromPercent the percentage the maker saw; an approval against a rollout that has moved
 *     since is refused, so nobody approves a step they did not look at
 */
public record RolloutChange(
    long id,
    String flag,
    int fromPercent,
    int toPercent,
    String proposedBy,
    Instant proposedAt,
    Status status,
    Optional<String> decidedBy,
    Optional<Instant> decidedAt) {

  public enum Status {
    PROPOSED,
    /** Approved by a checker and in effect. */
    APPLIED,
    /** The rollout moved (a lowering, another raise) before anyone approved this one. */
    SUPERSEDED
  }

  public RolloutChange {
    Objects.requireNonNull(flag, "flag");
    Objects.requireNonNull(proposedBy, "proposedBy");
    Objects.requireNonNull(proposedAt, "proposedAt");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(decidedBy, "decidedBy");
    Objects.requireNonNull(decidedAt, "decidedAt");
  }
}
