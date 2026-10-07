package com.rnd.chain.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MindDependencyPayloadTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldDeserializeMindDependencyPayload() throws Exception {

        String json = """
                {
                  "task_id": "123e4567-e89b-12d3-a456-426614174000",
                  "dependencies": [
                    {
                      "depends_on_task_id": "223e4567-e89b-12d3-a456-426614174000",
                      "confidence": 0.92
                    }
                  ]
                }
                """;

        MindDependencyPayload payload =
                objectMapper.readValue(json, MindDependencyPayload.class);

        assertNotNull(payload);

        assertEquals(
                UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
                payload.taskId()
        );

        assertNotNull(payload.dependencies());
        assertEquals(1, payload.dependencies().size());

        MindProposedDependency dependency =
                payload.dependencies().get(0);

        assertEquals(
                UUID.fromString("223e4567-e89b-12d3-a456-426614174000"),
                dependency.dependsOnTaskId()
        );

        assertEquals(0.92, dependency.confidence());
    }
}