package com.jerome.slledger.application.rollout;

import java.time.Instant;
import java.util.Objects;

/**
 * How far a flag is rolled out: the percentage of counterparties on the new behaviour, and since
 * when. A counterparty is on it when its {@link Bucket} is below the percentage, so raising the
 * percentage only ever adds counterparties.
 *
 * @param version the store's optimistic version: a change is applied to the rollout it was decided
 *     against, or not at all
 */
public record Rollout(String flag, int percent, Instant effectiveSince, long version) {

  public Rollout {
    Objects.requireNonNull(flag, "flag");
    Objects.requireNonNull(effectiveSince, "effectiveSince");
    if (percent < 0 || percent > 100) {
      throw new IllegalArgumentException("percent must be 0-100, was " + percent);
    }
  }
}
