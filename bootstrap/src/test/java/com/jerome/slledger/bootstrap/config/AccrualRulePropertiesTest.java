package com.jerome.slledger.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.domain.accrual.FlatRate;
import com.jerome.slledger.domain.money.Rate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindException;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class AccrualRulePropertiesTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withUserConfiguration(AccrualRuleConfig.class);

  @Test
  @DisplayName("valid bounds bind, and the default accrual rule is a flat rate at the lower bound")
  void validBoundsProduceFlatRate() {
    runner
        .withPropertyValues(
            "accrual.rate-lower-bound=0.0100",
            "accrual.rate-upper-bound=0.0800",
            "accrual.rounding-precision=2")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              assertThat(context.getBean(FlatRate.class))
                  .isEqualTo(new FlatRate(Rate.of("0.0100")));
            });
  }

  @Test
  @DisplayName("a lower bound above the upper bound refuses to start, naming the broken rule")
  void rejectsInvertedBounds() {
    runner
        .withPropertyValues(
            "accrual.rate-lower-bound=0.0800",
            "accrual.rate-upper-bound=0.0100",
            "accrual.rounding-precision=2")
        .run(context -> assertRejected(context.getStartupFailure(), "boundsOrdered"));
  }

  @Test
  @DisplayName("a negative rounding precision refuses to start, naming the field")
  void rejectsNegativePrecision() {
    runner
        .withPropertyValues(
            "accrual.rate-lower-bound=0.0100",
            "accrual.rate-upper-bound=0.0800",
            "accrual.rounding-precision=-1")
        .run(context -> assertRejected(context.getStartupFailure(), "roundingPrecision"));
  }

  private static void assertRejected(Throwable startupFailure, String field) {
    assertThat(startupFailure).isNotNull();
    assertThat(startupFailure).hasCauseInstanceOf(ConfigurationPropertiesBindException.class);
    assertThat(startupFailure)
        .rootCause()
        .isInstanceOf(BindValidationException.class)
        .hasMessageContaining(field);
  }
}
