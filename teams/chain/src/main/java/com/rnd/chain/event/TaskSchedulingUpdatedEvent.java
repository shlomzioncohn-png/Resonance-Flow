package com.rnd.chain.event;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

public class TaskSchedulingUpdatedEvent {

    @JsonProperty("task_id")
    private final UUID taskId;

    @JsonProperty("is_available")
    private final boolean isAvailable;

    @JsonProperty("priority_score")
    private final Integer priorityScore;

    public TaskSchedulingUpdatedEvent(
            UUID taskId,
            boolean isAvailable,
            Integer priorityScore) {

        this.taskId = taskId;
        this.isAvailable = isAvailable;
        this.priorityScore = priorityScore;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public boolean isAvailable() {
        return isAvailable;
    }

    public Integer getPriorityScore() {
        return priorityScore;
    }
}