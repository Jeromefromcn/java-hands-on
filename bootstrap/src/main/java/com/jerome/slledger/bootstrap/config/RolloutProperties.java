package com.jerome.slledger.bootstrap.config;

import com.jerome.slledger.application.rollout.RolloutRules;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Stage 14: the release rules for a rollout (see {@link RolloutRules}).
 *
 * @param firstStepCeiling the largest first step from zero, in percent
 * @param observation how long a step is in effect before the next raise may be approved
 */
@Validated
@ConfigurationProperties(prefix = "ledger.rollout")
public record RolloutProperties(
    @DefaultValue("5") @Min(1) @Max(100) int firstStepCeiling,
    @DefaultValue("30m") @NotNull Duration observation) {

  RolloutRules rules() {
    return new RolloutRules(firstStepCeiling, observation);
  }
}
