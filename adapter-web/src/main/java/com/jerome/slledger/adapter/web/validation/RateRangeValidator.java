package com.jerome.slledger.adapter.web.validation;

import com.jerome.slledger.domain.money.Rate;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * Built by Spring's validator factory, so its bounds are injected rather than written into the
 * annotation: one configuration governs both the binder and the validator.
 */
public class RateRangeValidator implements ConstraintValidator<RateRange, Rate> {

  private final RateBounds bounds;

  public RateRangeValidator(RateBounds bounds) {
    this.bounds = bounds;
  }

  @Override
  public boolean isValid(Rate value, ConstraintValidatorContext context) {
    if (value == null) {
      return true;
    }
    boolean valid =
        value.annual().compareTo(bounds.lower()) >= 0
            && value.annual().compareTo(bounds.upper()) <= 0;
    if (!valid) {
      context.disableDefaultConstraintViolation();
      context
          .buildConstraintViolationWithTemplate(
              "must be between %s and %s"
                  .formatted(bounds.lower().toPlainString(), bounds.upper().toPlainString()))
          .addConstraintViolation();
    }
    return valid;
  }
}
