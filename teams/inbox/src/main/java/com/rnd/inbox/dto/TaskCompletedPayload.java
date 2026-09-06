package com.rnd.inbox.dto;

import com.rnd.inbox.model.entity.Task;
import com.rnd.inbox.model.enums.TaskStatus;

import java.util.UUID;

public final class TaskCompletedPayload {

    private final UUID taskId;
    private final boolean isCompleted;

    private TaskCompletedPayload(UUID taskId, boolean isCompleted) {
        this.taskId = taskId;
        this.isCompleted = isCompleted;
    }

    public static TaskCompletedPayload from(Task task) {
        return new TaskCompletedPayload(task.getId(), task.getStatus() == TaskStatus.DONE);
    }

    public UUID getTaskId() {
        return taskId;
    }

    public boolean isCompleted() {
        return isCompleted;
    }
}
