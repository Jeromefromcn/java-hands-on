package com.jerome.slledger.adapter.web.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An {@code Idempotency-Key} value: 1 to 255 printable ASCII characters, no spaces. The key is
 * stored and logged, so control characters and unbounded length are refused at the door.
 */
@Documented
@Constraint(validatedBy = {})
@Size(min = 1, max = 255)
@Pattern(regexp = "[\\x21-\\x7E]+")
@ReportAsSingleViolation
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface IdempotencyKeyHeader {

  String message() default "must be 1 to 255 printable ASCII characters without spaces";

  Class<?>[] groups() default {};

  Class<? extends Payload>[] payload() default {};
}
