package com.rnd.chain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.UUID;

public record MindProposedDependency(

        @JsonProperty("depends_on_task_id")
        UUID dependsOnTaskId,

        @JsonProperty("confidence")
        Double confidence

) {
}