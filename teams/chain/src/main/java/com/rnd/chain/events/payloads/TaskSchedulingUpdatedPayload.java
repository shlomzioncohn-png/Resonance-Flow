package com.rnd.chain.events.payloads;

import java.util.UUID;

/**
 * Payload for the task.scheduling.updated event.
 *
 * TODO: taskId is UUID for now, matching the placeholder used across
 * DependencyGraphService/ConstraintService until Inbox.TaskId is finalized.
 */
public class TaskSchedulingUpdatedPayload {

    private final UUID taskId;
    private final boolean isAvailable;
    private final int priorityScore;

    public TaskSchedulingUpdatedPayload(UUID taskId, boolean isAvailable, int priorityScore) {
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

    public int getPriorityScore() {
        return priorityScore;
    }
}