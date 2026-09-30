package com.jerome.slledger.adapter.web.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** A rate within the configured {@link RateBounds}. Null is left to {@code @NotNull}. */
@Documented
@Constraint(validatedBy = RateRangeValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface RateRange {

  String message() default "must be within the configured accrual rate bounds";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
