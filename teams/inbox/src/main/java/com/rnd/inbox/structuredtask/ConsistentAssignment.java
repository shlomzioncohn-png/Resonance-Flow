package com.rnd.inbox.structuredtask;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.TYPE;

@Documented
@Constraint(validatedBy = ConsistentAssignmentValidator.class)
@Target({TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ConsistentAssignment {

    String message() default "assignment must have exactly one of assigneeUserId/assigneeTeamId, and assignmentRole";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
