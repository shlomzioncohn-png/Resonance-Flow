package com.rnd.inbox.shared.messaging.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;

@Documented
@Constraint(validatedBy = IsoInstantValidator.class)
@Target({FIELD, METHOD, PARAMETER, RECORD_COMPONENT, ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface IsoInstant {

    String message() default "must be a valid ISO-8601 instant";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
