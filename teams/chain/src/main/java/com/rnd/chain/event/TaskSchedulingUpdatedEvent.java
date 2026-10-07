package com.rnd.chain.event;

import java.util.Objects;
import java.util.UUID;

public class TaskSchedulingUpdatedEvent {

    private final UUID eventId;

    private final UUID taskId;

    private final boolean isAvailable;

    private final Double priorityScore;

    public TaskSchedulingUpdatedEvent(
            UUID eventId,
            UUID taskId,
            boolean isAvailable,
            Double priorityScore) {

        this.eventId = Objects.requireNonNull(eventId, "eventId must not be null");
        this.taskId = Objects.requireNonNull(taskId, "taskId must not be null");
        this.isAvailable = isAvailable;
        this.priorityScore = priorityScore;
    }

    public UUID getEventId() {
        return eventId;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public boolean isAvailable() {
        return isAvailable;
    }

    public Double getPriorityScore() {
        return priorityScore;
    }
}
