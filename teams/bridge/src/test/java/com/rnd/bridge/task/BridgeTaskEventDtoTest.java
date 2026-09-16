package com.rnd.bridge.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rnd.bridge.task.model.ExtractionProvenance;
import com.rnd.bridge.task.model.RoleContext;
import com.rnd.bridge.task.model.SourceType;
import com.rnd.bridge.task.model.Task;
import com.rnd.bridge.task.model.TaskStatus;
import com.rnd.bridge.task.model.Tier;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins BridgeTaskEventDto's wire shape against the Inbox Kafka ingestion
 * contract (esti/inbox-kafka-ingestion-contract, topic
 * bridge.structured.tasks): snake_case keys, UPPER_SNAKE_CASE enum values,
 * and none of Inbox's self-generated fields - matching what
 * TaskSerializationTest pins for Task itself.
 */
class BridgeTaskEventDtoTest {

    // Mirrors the globally configured Spring Boot ObjectMapper
    // (spring.jackson.property-naming-strategy: SNAKE_CASE), not bare
    // Jackson defaults.
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final Set<String> CONTRACT_KEYS = Set.of(
            "event_id", "organization_id", "project_id", "title", "description", "source_type",
            "source_ref", "tier", "role_context", "urgency", "effort_estimate_minutes",
            "deadline", "status", "confidence");

    private static final Set<String> FORBIDDEN_KEYS = Set.of(
            "id", "correlation_id", "is_available", "priority_score", "owner_id", "created_by",
            "extraction_provenance", "created_at", "updated_at", "completed_at", "snoozed_until");

    private static Task fullyPopulatedTask() {
        return new Task(
                "org-1",
                "project-1",
                "Full task title",
                "Full task description",
                TaskStatus.NEW,
                3,
                1.0,
                Tier.TACTICAL,
                ExtractionProvenance.ETL,
                RoleContext.SALESPERSON,
                SourceType.CRM_DEAL,
                "crm:D-1",
                30,
                Instant.parse("2026-08-24T09:00:00Z"),
                Instant.parse("2026-08-25T09:00:00Z"),
                Instant.parse("2026-08-23T09:00:00Z"));
    }

    @Test
    void everyJsonKeyMatchesTheContractExactlyWithNoExtraKeys() {
        JsonNode json = MAPPER.valueToTree(BridgeTaskEventDto.from(fullyPopulatedTask()));

        Set<String> actualKeys = new TreeSet<>();
        json.fieldNames().forEachRemaining(actualKeys::add);

        assertEquals(new TreeSet<>(CONTRACT_KEYS), actualKeys);
    }

    @Test
    void noForbiddenInboxOwnedFieldIsPresentEvenThoughSomeExistOnTask() {
        JsonNode json = MAPPER.valueToTree(BridgeTaskEventDto.from(fullyPopulatedTask()));

        for (String forbidden : FORBIDDEN_KEYS) {
            assertFalse(json.has(forbidden), () -> forbidden + " must not be sent - Inbox generates it");
        }
    }

    @Test
    void enumValuesSerializeUpperSnakeCaseNotTasksLowercase() {
        JsonNode json = MAPPER.valueToTree(BridgeTaskEventDto.from(fullyPopulatedTask()));

        assertEquals("CRM_DEAL", json.get("source_type").asText());
        assertEquals("NEW", json.get("status").asText());
        assertEquals("TACTICAL", json.get("tier").asText());
        assertEquals("SALESPERSON", json.get("role_context").asText());
    }

    @Test
    void deadlineSerializesAsIso8601StringWithTrailingZ() {
        JsonNode json = MAPPER.valueToTree(BridgeTaskEventDto.from(fullyPopulatedTask()));

        assertTrue(json.get("deadline").isTextual(),
                () -> "deadline should be an ISO-8601 string, not an object: " + json.get("deadline"));
        assertEquals("2026-08-24T09:00:00Z", json.get("deadline").asText());
    }

    @Test
    void eventIdIsAFreshValidUuidOnEveryPublishOfTheSameTask() {
        Task task = fullyPopulatedTask();

        BridgeTaskEventDto first = BridgeTaskEventDto.from(task);
        BridgeTaskEventDto second = BridgeTaskEventDto.from(task);

        assertDoesNotThrow(() -> UUID.fromString(first.eventId()));
        assertDoesNotThrow(() -> UUID.fromString(second.eventId()));
        assertNotEquals(first.eventId(), second.eventId());
    }
}
