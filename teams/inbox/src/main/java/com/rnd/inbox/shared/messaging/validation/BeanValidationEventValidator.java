package com.rnd.inbox.shared.messaging.validation;

import com.rnd.inbox.shared.messaging.kafka.EventValidationException;
import com.rnd.inbox.shared.messaging.kafka.EventValidator;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import java.util.List;
import java.util.Set;

public class BeanValidationEventValidator<T> implements EventValidator<T> {

    private final Validator validator;

    public BeanValidationEventValidator(Validator validator) {
        this.validator = validator;
    }

    @Override
    public void validate(T event) {
        Set<ConstraintViolation<T>> violations = validator.validate(event);
        if (violations.isEmpty()) {
            return;
        }

        List<String> fieldErrors = violations.stream()
                .map(violation -> violation.getPropertyPath() + " " + violation.getMessage())
                .sorted()
                .toList();

        throw new EventValidationException(
                event.getClass().getSimpleName() + " failed validation", fieldErrors);
    }
}
