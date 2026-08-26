package com.rnd.chain.dto;

import java.util.UUID;

public class TaskDependency {

    private UUID taskId;
    private UUID dependsOnTaskId;

    public TaskDependency() {
    }

    public TaskDependency(
            UUID taskId,
            UUID dependsOnTaskId
    ) {
        this.taskId = taskId;
        this.dependsOnTaskId = dependsOnTaskId;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public void setTaskId(UUID taskId) {
        this.taskId = taskId;
    }

    public UUID getDependsOnTaskId() {
        return dependsOnTaskId;
    }

    public void setDependsOnTaskId(UUID dependsOnTaskId) {
        this.dependsOnTaskId = dependsOnTaskId;
    }
}