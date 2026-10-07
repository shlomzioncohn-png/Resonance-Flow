package com.rnd.chain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.UUID;

public record MindDependencyPayload(

        @JsonProperty("task_id")
        UUID taskId,

        @JsonProperty("dependencies")
        List<MindProposedDependency> dependencies

) {
}