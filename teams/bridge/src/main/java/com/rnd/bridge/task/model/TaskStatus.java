package com.rnd.bridge.task.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum TaskStatus {
    NEW,
    IN_REVIEW,
    APPROVED,
    IN_PROGRESS,
    SNOOZED,
    DONE,
    REJECTED,
    CANCELLED;

    @JsonValue
    public String toJson() {
        return name().toLowerCase();
    }
}
