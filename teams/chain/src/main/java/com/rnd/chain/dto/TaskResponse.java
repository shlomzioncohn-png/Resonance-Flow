package com.rnd.chain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

public class TaskResponse {

    private UUID id;
    private UUID parentTaskId;
    private TaskStatus status;

    @JsonProperty("isReady")
    private boolean isReady;

    private int blockingCount;
    private int openConstraintCount;
    private Integer estimatedDurationMin;
    private Double  score;

    public TaskResponse() {
    }

    public TaskResponse(
            UUID id,
            UUID parentTaskId,
            TaskStatus status,
            boolean isReady,
            int blockingCount,
            int openConstraintCount,
            Integer estimatedDurationMin,
            Double score
    ) {
        this.id = id;
        this.parentTaskId = parentTaskId;
        this.status = status;
        this.isReady = isReady;
        this.blockingCount = blockingCount;
        this.openConstraintCount = openConstraintCount;
        this.estimatedDurationMin = estimatedDurationMin;
        this.score = score;
    }

    public static TaskResponse from(Task task) {
        return new TaskResponse(
                task.getId(),
                task.getParentTaskId(),
                task.getStatus(),
                task.isReady(),
                task.getBlockingCount(),
                task.getOpenConstraintCount(),
                task.getEstimatedDurationMin(),
                task.getScore()
        );
    }

    public UUID getId() {
        return id;
    }

    public UUID getParentTaskId() {
        return parentTaskId;
    }

    public TaskStatus getStatus() {
        return status;
    }

    @JsonProperty("isReady")
    public boolean isReady() {
        return isReady;
    }

    public int getBlockingCount() {
        return blockingCount;
    }

    public int getOpenConstraintCount() {
        return openConstraintCount;
    }

    public Integer getEstimatedDurationMin() {
        return estimatedDurationMin;
    }

    public Double getScore() {
        return score;
    }
}