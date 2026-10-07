package com.rnd.chain.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MindProposedDependencyTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldDeserializeDependencyWithConfidence() throws Exception {

        String json = """
                {
                  "depends_on_task_id":
                    "223e4567-e89b-12d3-a456-426614174000",
                  "confidence": 0.75
                }
                """;

        MindProposedDependency dependency =
                objectMapper.readValue(
                        json,
                        MindProposedDependency.class
                );

        assertNotNull(dependency);

        assertEquals(
                UUID.fromString(
                        "223e4567-e89b-12d3-a456-426614174000"
                ),
                dependency.dependsOnTaskId()
        );

        assertEquals(0.75, dependency.confidence());
    }

    @Test
    void shouldKeepConfidenceNullWhenMissing() throws Exception {

        String json = """
                {
                  "depends_on_task_id":
                    "223e4567-e89b-12d3-a456-426614174000"
                }
                """;

        MindProposedDependency dependency =
                objectMapper.readValue(
                        json,
                        MindProposedDependency.class
                );

        assertNotNull(dependency);

        assertEquals(
                UUID.fromString(
                        "223e4567-e89b-12d3-a456-426614174000"
                ),
                dependency.dependsOnTaskId()
        );

        assertNull(dependency.confidence());
    }
}