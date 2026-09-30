package com.jerome.slledger.adapter.web.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.jerome.slledger.domain.money.Rate;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.util.Set;
import org.hibernate.validator.HibernateValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RateRangeValidatorTest {

  private record Holder(@RateRange Rate rate) {}

  @Test
  @DisplayName("the bounds come from the RateBounds given, both ends inclusive")
  void boundsFollowTheBean() {
    Validator narrow =
        validatorWith(new RateBounds(new BigDecimal("0.01"), new BigDecimal("0.05")));
    Validator wide = validatorWith(new RateBounds(BigDecimal.ZERO, new BigDecimal("0.20")));

    assertThat(narrow.validate(new Holder(Rate.of("0.01")))).isEmpty();
    assertThat(narrow.validate(new Holder(Rate.of("0.0500")))).isEmpty();
    assertThat(narrow.validate(new Holder(Rate.of("0.0501")))).hasSize(1);
    assertThat(narrow.validate(new Holder(Rate.of("0.0099")))).hasSize(1);
    assertThat(wide.validate(new Holder(Rate.of("0.0501")))).isEmpty();
  }

  @Test
  @DisplayName("the message names the configured bounds")
  void messageNamesBounds() {
    Set<ConstraintViolation<Holder>> violations =
        validatorWith(new RateBounds(new BigDecimal("0.01"), new BigDecimal("0.05")))
            .validate(new Holder(Rate.of("0.2")));

    assertThat(violations.iterator().next().getMessage())
        .isEqualTo("must be between 0.01 and 0.05");
  }

  private static Validator validatorWith(RateBounds bounds) {
    ValidatorFactory factory =
        Validation.byProvider(HibernateValidator.class)
            .configure()
            .constraintValidatorFactory(
                new jakarta.validation.ConstraintValidatorFactory() {
                  @Override
                  public <T extends jakarta.validation.ConstraintValidator<?, ?>> T getInstance(
                      Class<T> key) {
                    if (key == RateRangeValidator.class) {
                      return key.cast(new RateRangeValidator(bounds));
                    }
                    try {
                      return key.getDeclaredConstructor().newInstance();
                    } catch (ReflectiveOperationException e) {
                      throw new IllegalStateException(e);
                    }
                  }

                  @Override
                  public void releaseInstance(
                      jakarta.validation.ConstraintValidator<?, ?> instance) {}
                })
            .buildValidatorFactory();
    return factory.getValidator();
  }
}
