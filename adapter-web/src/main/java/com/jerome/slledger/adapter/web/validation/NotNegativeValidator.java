package com.jerome.slledger.adapter.web.validation;

import com.jerome.slledger.domain.money.Money;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class NotNegativeValidator implements ConstraintValidator<NotNegative, Money> {

  @Override
  public boolean isValid(Money value, ConstraintValidatorContext context) {
    return value == null || !value.isNegative();
  }
}
