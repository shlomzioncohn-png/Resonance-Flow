package com.rnd.inbox.task;

import com.rnd.inbox.shared.messaging.kafka.EventValidationException;
import com.rnd.inbox.shared.messaging.kafka.EventValidator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

public class AssigneeConsistencyValidator<T> implements EventValidator<T> {

    private final Function<T, List<AssigneeDto>> assigneesExtractor;

    public AssigneeConsistencyValidator(Function<T, List<AssigneeDto>> assigneesExtractor) {
        this.assigneesExtractor = assigneesExtractor;
    }

    @Override
    public void validate(T event) {
        List<AssigneeDto> assignees = assigneesExtractor.apply(event);
        if (assignees == null || assignees.isEmpty()) {
            return;
        }

        List<String> fieldErrors = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (int i = 0; i < assignees.size(); i++) {
            AssigneeDto assignee = assignees.get(i);
            if (assignee == null) {
                fieldErrors.add("assignees[" + i + "] must not be null");
                continue;
            }
            if (!seen.add(dedupeKey(assignee))) {
                fieldErrors.add("assignees[" + i + "] duplicates another entry with the same "
                        + "assigneeType and id (" + assignee.getAssigneeType() + ":" + assignee.getId() + ")");
            }
        }

        if (!fieldErrors.isEmpty()) {
            throw new EventValidationException(
                    event.getClass().getSimpleName() + " failed validation", fieldErrors);
        }
    }

    private String dedupeKey(AssigneeDto assignee) {
        String type = assignee.getAssigneeType() == null ? "" : assignee.getAssigneeType().trim().toUpperCase();
        String id = assignee.getId() == null ? "" : assignee.getId().trim().toLowerCase();
        return type + ":" + id;
    }
}
