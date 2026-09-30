package com.jerome.slledger.adapter.web.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An ISIN whose shape and check digit are both right.
 *
 * <p>{@code domain}'s {@code Isin} checks the shape only, so that synthetic fixtures need no valid
 * digit. Input from outside is held to more: a transposed digit in a client's request is exactly
 * what the check digit exists to catch, and the boundary is where it can still be refused. See
 * {@code docs/decisions/07-web.md}. Null is left to {@code @NotNull}.
 */
@Documented
@Constraint(validatedBy = IsinValidator.class)
@Target({
  ElementType.FIELD,
  ElementType.PARAMETER,
  ElementType.RECORD_COMPONENT,
  ElementType.TYPE_USE
})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidIsin {

  String message() default "must be an ISIN with a valid check digit";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
