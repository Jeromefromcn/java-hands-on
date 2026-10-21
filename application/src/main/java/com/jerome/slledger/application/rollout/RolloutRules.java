package com.jerome.slledger.application.rollout;

import com.jerome.slledger.application.rollout.RolloutRefusedException.Reason;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * The release rules for a change to a money rule, as checks: pure, no store, no clock of its own.
 *
 * <ul>
 *   <li><b>Maker is not checker.</b> A raise is proposed by one person and approved by another.
 *   <li><b>A small first step.</b> From zero, at most {@code firstStepCeiling} percent.
 *   <li><b>Observation before every raise.</b> A raise is approved only when the current step has
 *       been in effect for {@code observation}; a step nobody watched is a step nobody judged.
 *   <li><b>Lowering is one person and immediate.</b> Restricting a change can only reduce exposure;
 *       a kill switch that waits for a second person is a ticket.
 * </ul>
 *
 * See docs/decisions/14-delivery.md.
 */
public record RolloutRules(int firstStepCeiling, Duration observation) {

  public RolloutRules {
    Objects.requireNonNull(observation, "observation");
    if (firstStepCeiling < 1 || firstStepCeiling > 100) {
      throw new IllegalArgumentException("firstStepCeiling must be 1-100");
    }
  }

  public void checkProposal(Rollout current, int toPercent) {
    if (toPercent <= current.percent() || toPercent > 100) {
      throw new RolloutRefusedException(
          Reason.NOT_A_RAISE,
          "A raise goes above %d%% and at most to 100%%".formatted(current.percent()));
    }
    if (current.percent() == 0 && toPercent > firstStepCeiling) {
      throw new RolloutRefusedException(
          Reason.FIRST_STEP_TOO_LARGE,
          "The first step is at most %d%%".formatted(firstStepCeiling));
    }
  }

  public void checkApproval(Rollout current, RolloutChange change, String checker, Instant now) {
    if (change.status() != RolloutChange.Status.PROPOSED
        || change.fromPercent() != current.percent()) {
      throw new RolloutRefusedException(
          Reason.STALE, "The rollout is no longer at the percentage this change was proposed on");
    }
    if (change.proposedBy().equals(checker)) {
      throw new RolloutRefusedException(
          Reason.SAME_PERSON, "A raise is approved by someone other than who proposed it");
    }
    // Checked again here, not only at the proposal: the rule could have been met by a proposal made
    // and then left lying until it was not.
    checkProposal(current, change.toPercent());
    Instant observedUntil = current.effectiveSince().plus(observation);
    if (current.percent() > 0 && now.isBefore(observedUntil)) {
      throw new RolloutRefusedException(
          Reason.OBSERVATION_PERIOD,
          "The %d%% step is observed until %s".formatted(current.percent(), observedUntil));
    }
  }

  public void checkLowering(Rollout current, int toPercent) {
    if (toPercent >= current.percent() || toPercent < 0) {
      throw new RolloutRefusedException(
          Reason.NOT_A_LOWERING,
          "A lowering goes below %d%% and at least to 0%%".formatted(current.percent()));
    }
  }
}
