package com.jerome.slledger.application.rollout;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.jerome.slledger.application.rollout.RolloutRefusedException.Reason;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RolloutRulesTest {

  private static final Instant T0 = Instant.parse("2026-10-21T01:00:00Z");
  private static final RolloutRules RULES = new RolloutRules(5, Duration.ofMinutes(30));

  private static Rollout at(int percent, Instant since) {
    return new Rollout("f", percent, since, 3);
  }

  private static RolloutChange proposed(int from, int to, String maker) {
    return new RolloutChange(
        1,
        "f",
        from,
        to,
        maker,
        T0,
        RolloutChange.Status.PROPOSED,
        Optional.empty(),
        Optional.empty());
  }

  @Test
  void theFirstStepIsAtMostTheCeiling() {
    assertThatCode(() -> RULES.checkProposal(at(0, T0), 5)).doesNotThrowAnyException();
    assertThatExceptionOfType(RolloutRefusedException.class)
        .isThrownBy(() -> RULES.checkProposal(at(0, T0), 6))
        .extracting(RolloutRefusedException::reason)
        .isEqualTo(Reason.FIRST_STEP_TOO_LARGE);
  }

  @Test
  void aRaiseRaisesAndStaysWithinOneHundred() {
    assertThatExceptionOfType(RolloutRefusedException.class)
        .isThrownBy(() -> RULES.checkProposal(at(25, T0), 25))
        .extracting(RolloutRefusedException::reason)
        .isEqualTo(Reason.NOT_A_RAISE);
    assertThatExceptionOfType(RolloutRefusedException.class)
        .isThrownBy(() -> RULES.checkProposal(at(25, T0), 101))
        .extracting(RolloutRefusedException::reason)
        .isEqualTo(Reason.NOT_A_RAISE);
    // After the first step any size is allowed: the observation period, not the size, is the
    // rule from there on.
    assertThatCode(() -> RULES.checkProposal(at(5, T0), 100)).doesNotThrowAnyException();
  }

  @Test
  void theMakerCannotApproveTheirOwnRaise() {
    assertThatExceptionOfType(RolloutRefusedException.class)
        .isThrownBy(() -> RULES.checkApproval(at(0, T0), proposed(0, 5, "ops1"), "ops1", T0))
        .extracting(RolloutRefusedException::reason)
        .isEqualTo(Reason.SAME_PERSON);
    assertThatCode(() -> RULES.checkApproval(at(0, T0), proposed(0, 5, "ops1"), "ops2", T0))
        .doesNotThrowAnyException();
  }

  @Test
  void aRaiseWaitsUntilTheCurrentStepWasObserved() {
    Rollout fivePercentSinceT0 = at(5, T0);
    RolloutChange toTwentyFive = proposed(5, 25, "ops1");

    assertThatExceptionOfType(RolloutRefusedException.class)
        .isThrownBy(
            () ->
                RULES.checkApproval(
                    fivePercentSinceT0, toTwentyFive, "ops2", T0.plus(Duration.ofMinutes(29))))
        .extracting(RolloutRefusedException::reason)
        .isEqualTo(Reason.OBSERVATION_PERIOD);
    assertThatCode(
            () ->
                RULES.checkApproval(
                    fivePercentSinceT0, toTwentyFive, "ops2", T0.plus(Duration.ofMinutes(30))))
        .doesNotThrowAnyException();
  }

  @Test
  void anApprovalOfAStepThatIsNoLongerTheCurrentOneIsStale() {
    // Proposed at 5%, then someone lowered to 0: approving "5 -> 25" now would be a 25% first step.
    assertThatExceptionOfType(RolloutRefusedException.class)
        .isThrownBy(() -> RULES.checkApproval(at(0, T0), proposed(5, 25, "ops1"), "ops2", T0))
        .extracting(RolloutRefusedException::reason)
        .isEqualTo(Reason.STALE);
  }

  @Test
  void loweringIsImmediateAndNeedsNoObservation() {
    assertThatCode(() -> RULES.checkLowering(at(25, T0), 0)).doesNotThrowAnyException();
    assertThatExceptionOfType(RolloutRefusedException.class)
        .isThrownBy(() -> RULES.checkLowering(at(0, T0), 0))
        .extracting(RolloutRefusedException::reason)
        .isEqualTo(Reason.NOT_A_LOWERING);
  }
}
