package com.rnd.inbox.structuredtask;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class ConsistentAssignmentValidator implements ConstraintValidator<ConsistentAssignment, StructuredTaskEventDto> {

    @Override
    public boolean isValid(StructuredTaskEventDto dto, ConstraintValidatorContext context) {
        boolean hasUser = isPresent(dto.getAssigneeUserId());
        boolean hasTeam = isPresent(dto.getAssigneeTeamId());
        boolean hasRole = isPresent(dto.getAssignmentRole());

        if (!hasUser && !hasTeam && !hasRole) {
            return true;
        }

        context.disableDefaultConstraintViolation();
        boolean valid = true;

        if (hasUser == hasTeam) {
            context.buildConstraintViolationWithTemplate(
                            "exactly one of assigneeUserId or assigneeTeamId is required when an assignment is present")
                    .addPropertyNode("assigneeUserId")
                    .addConstraintViolation();
            valid = false;
        }
        if (!hasRole) {
            context.buildConstraintViolationWithTemplate("assignmentRole is required when an assignment is present")
                    .addPropertyNode("assignmentRole")
                    .addConstraintViolation();
            valid = false;
        }
        return valid;
    }

    private boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
