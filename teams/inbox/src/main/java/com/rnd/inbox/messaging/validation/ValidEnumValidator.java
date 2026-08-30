package com.rnd.inbox.messaging.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Arrays;
import java.util.List;

public class ValidEnumValidator implements ConstraintValidator<ValidEnum, String> {

    private List<String> allowed;

    @Override
    public void initialize(ValidEnum annotation) {
        allowed = Arrays.stream(annotation.enumClass().getEnumConstants())
                .map(Enum::name)
                .toList();
    }

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank() || allowed.contains(value)) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate("must be one of " + allowed + " but was '" + value + "'")
                .addConstraintViolation();
        return false;
    }
}
