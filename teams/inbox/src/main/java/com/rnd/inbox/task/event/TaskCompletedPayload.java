package com.rnd.inbox.task.event;
import com.rnd.inbox.task.model.Task;
import com.rnd.inbox.task.model.TaskStatus;
import java.time.Instant;
import java.util.UUID;

public final class TaskCompletedPayload {

    private final UUID taskId;
    private final boolean isCompleted;
    private final Instant updatedAt;

    private TaskCompletedPayload(UUID taskId, boolean isCompleted, Instant updatedAt) {
        this.taskId = taskId;
        this.isCompleted = isCompleted;
        this.updatedAt = updatedAt;
    }

    public static TaskCompletedPayload from(Task task) {
        return new TaskCompletedPayload(task.getId(), task.getStatus() == TaskStatus.DONE, task.getUpdatedAt());
    }

    public UUID getTaskId() {
        return taskId;
    }

    public boolean isCompleted() {
        return isCompleted;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
