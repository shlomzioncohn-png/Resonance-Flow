package com.rnd.inbox.shared.messaging.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.Arrays;
import java.util.List;

public class ValidEnumValidator implements ConstraintValidator<ValidEnum, String> {

    private List<String> allowed;

    @Override
    public void initialize(ValidEnum annotation) {
        List<String> constants = Arrays.stream(annotation.enumClass().getEnumConstants())
                .map(Enum::name)
                .toList();

        if (annotation.allowed().length == 0) {
            allowed = constants;
            return;
        }

        List<String> subset = List.of(annotation.allowed());
        List<String> unknown = subset.stream().filter(name -> !constants.contains(name)).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalStateException("@ValidEnum(allowed=...) on " + annotation.enumClass().getSimpleName()
                    + " lists values that are not constants of that enum: " + unknown);
        }
        allowed = subset;
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
