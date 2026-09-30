package com.jerome.slledger.adapter.web.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.domain.money.Money;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;
import jakarta.validation.constraints.Positive;
import java.util.Currency;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Validation groups, on a test-only type. Product code uses two request types, one per endpoint,
 * instead; see docs/decisions/07-web.md for why.
 */
class ValidationGroupsTest {

  interface OnOpen {}

  interface OnReturn {}

  /** One shape for both instructions, with the rules told apart by group. */
  record TradeInstruction(
      @NotNull(groups = {OnOpen.class, OnReturn.class})
          @Positive(groups = {OnOpen.class, OnReturn.class})
          Long quantity,
      @NotNull(groups = OnOpen.class) @Null(groups = OnReturn.class) Money collateral,
      @NotNull(groups = OnOpen.class) @Null(groups = OnReturn.class) String rule) {}

  private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

  private static final TradeInstruction AN_OPEN =
      new TradeInstruction(100L, Money.of("1000.00", Currency.getInstance("HKD")), "FLAT 0.0365");
  private static final TradeInstruction A_RETURN = new TradeInstruction(100L, null, null);

  @Test
  @DisplayName("each group accepts what the other refuses")
  void groupsDisagree() {
    assertThat(fields(validator.validate(AN_OPEN, OnOpen.class))).isEmpty();
    assertThat(fields(validator.validate(AN_OPEN, OnReturn.class)))
        .containsExactlyInAnyOrder("collateral", "rule");

    assertThat(fields(validator.validate(A_RETURN, OnReturn.class))).isEmpty();
    assertThat(fields(validator.validate(A_RETURN, OnOpen.class)))
        .containsExactlyInAnyOrder("collateral", "rule");
  }

  @Test
  @DisplayName("the shared rule holds in both groups, and no group means no rules at all")
  void sharedRuleAndDefaultGroup() {
    TradeInstruction zero = new TradeInstruction(0L, null, null);

    assertThat(fields(validator.validate(zero, OnReturn.class))).containsExactly("quantity");
    // The trap: validated without naming a group, a type whose every constraint has one checks
    // nothing. @Valid on a controller parameter validates the Default group.
    assertThat(validator.validate(zero)).isEmpty();
  }

  private static Set<String> fields(Set<ConstraintViolation<TradeInstruction>> violations) {
    return violations.stream().map(v -> v.getPropertyPath().toString()).collect(Collectors.toSet());
  }
}
