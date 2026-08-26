package com.rnd.chain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

public class TaskEligibilityResponse {

    private final UUID taskId;
    private final boolean isReady;
    private final int blockingCount;
    private final int openConstraintCount;

    public TaskEligibilityResponse(
            UUID taskId,
            boolean isReady,
            int blockingCount,
            int openConstraintCount
    ) {
        this.taskId = taskId;
        this.isReady = isReady;
        this.blockingCount = blockingCount;
        this.openConstraintCount = openConstraintCount;
    }

    public UUID getTaskId() {
        return taskId;
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
    
}