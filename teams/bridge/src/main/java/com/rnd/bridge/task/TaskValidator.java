package com.rnd.bridge.task;

import com.rnd.bridge.core.PayloadValidator;
import com.rnd.bridge.core.error.ValidationException;
import com.rnd.bridge.task.model.Task;
import com.rnd.bridge.task.model.TaskStatus;
import org.springframework.stereotype.Component;

/**
 * Fails fast on a malformed {@link Task} before it leaves the mapper, rather
 * than letting Inbox reject it later with a bare constraint-violation message
 * and no context about which field or deal produced it.
 */
@Component
public final class TaskValidator implements PayloadValidator<Task> {

    @Override
    public void validate(Task task) {
        requireNonNull(task.title(), "title");
        requireNonNull(task.sourceType(), "sourceType");
        requireNonNull(task.status(), "status");
        requireNonNull(task.extractionProvenance(), "extractionProvenance");

        if (task.urgency() != null && (task.urgency() < 1 || task.urgency() > 5)) {
            throw invalid("urgency", task.urgency(), "must be between 1 and 5");
        }
        if (task.confidence() != null && (task.confidence() < 0.0 || task.confidence() > 1.0)) {
            throw invalid("confidence", task.confidence(), "must be between 0 and 1");
        }
        if (task.effortEstimateMinutes() != null && task.effortEstimateMinutes() <= 0) {
            throw invalid("effortEstimateMinutes", task.effortEstimateMinutes(), "must be > 0");
        }

        boolean snoozed = task.status() == TaskStatus.SNOOZED;
        boolean hasSnoozedUntil = task.snoozedUntil() != null;
        if (snoozed != hasSnoozedUntil) {
            throw invalid("snoozedUntil", task.snoozedUntil(),
                    "must be present if and only if status = SNOOZED (status was " + task.status() + ")");
        }
    }

    private static void requireNonNull(Object value, String field) {
        if (value == null) {
            throw invalid(field, null, "must not be null");
        }
    }

    private static ValidationException invalid(String field, Object value, String reason) {
        return new ValidationException("Invalid Task." + field + " = " + value + ": " + reason);
    }
}
