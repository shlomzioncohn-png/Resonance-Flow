package com.rnd.inbox.messaging.validation;

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
@Constraint(validatedBy = ValidEnumValidator.class)
@Target({FIELD, METHOD, PARAMETER, RECORD_COMPONENT, ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidEnum {

    Class<? extends Enum<?>> enumClass();

    String message() default "must be one of the supported values";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
