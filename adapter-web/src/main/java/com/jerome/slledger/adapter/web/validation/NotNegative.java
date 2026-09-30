package com.jerome.slledger.adapter.web.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A {@code Money} amount of zero or more. {@code domain} refuses a negative collateral too, but
 * with an {@code IllegalArgumentException}, which is a programming error there and would be a
 * {@code 500} here; the boundary refuses it first, as the client's mistake it is.
 */
@Documented
@Constraint(validatedBy = NotNegativeValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface NotNegative {

  String message() default "must not be negative";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
