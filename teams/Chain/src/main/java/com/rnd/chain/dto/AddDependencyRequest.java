package com.rnd.chain.dto;
import javax.validation.constraints.NotNull;
import java.util.UUID;

public class AddDependencyRequest {

    @NotNull
    private UUID dependsOnTaskId;

    public AddDependencyRequest() {
    }

    public UUID getDependsOnTaskId() {
        return dependsOnTaskId;
    }

    public void setDependsOnTaskId(UUID dependsOnTaskId) {
        this.dependsOnTaskId = dependsOnTaskId;
    }
}