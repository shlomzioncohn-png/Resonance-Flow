package com.rnd.chain.dto;

public class AddDependencyRequest {

    @NotNull(message = "dependsOnTaskId is required")
    private Inbox.TaskId dependsOnTaskId;

    public AddDependencyRequest() {
    }

    public Inbox.TaskId getDependsOnTaskId() {
        return dependsOnTaskId;
    }

    public void setDependsOnTaskId(Inbox.TaskId dependsOnTaskId) {
        this.dependsOnTaskId = dependsOnTaskId;
    }
}