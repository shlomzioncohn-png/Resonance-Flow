package com.rnd.bridge.task.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The test that would have caught the wire-contract gap: nothing else in
 * this tree serializes a Task to JSON, so nothing else would have noticed
 * camelCase keys or uppercase enum values reaching Inbox.
 */
class TaskSerializationTest {

    // Registers JavaTimeModule and disables WRITE_DATES_AS_TIMESTAMPS
    // explicitly: Spring's auto-configured ObjectMapper does both by
    // default, but a bare `new ObjectMapper()` does neither, so this
    // mirrors what a correctly configured mapper must do, not what the
    // Jackson defaults happen to be.
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private static final Set<String> INBOX_COLUMN_NAMES = Set.of(
            "organization_id", "project_id", "title", "description", "status",
            "urgency", "confidence", "tier", "extraction_provenance", "role_context",
            "source_type", "source_ref", "effort_estimate_minutes", "deadline",
            "completed_at");

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
                Instant.parse("2026-08-23T09:00:00Z"));
    }

    @Test
    void everyJsonKeyMatchesItsInboxColumnName() {
        JsonNode json = MAPPER.valueToTree(fullyPopulatedTask());

        assertTrue(json.has("organization_id"));
        assertTrue(json.has("project_id"));
        assertTrue(json.has("title"));
        assertTrue(json.has("description"));
        assertTrue(json.has("status"));
        assertTrue(json.has("urgency"));
        assertTrue(json.has("confidence"));
        assertTrue(json.has("tier"));
        assertTrue(json.has("extraction_provenance"));
        assertTrue(json.has("role_context"));
        assertTrue(json.has("source_type"));
        assertTrue(json.has("source_ref"));
        assertTrue(json.has("effort_estimate_minutes"));
        assertTrue(json.has("deadline"));
        assertTrue(json.has("completed_at"));
    }

    @Test
    void noUnexpectedExtraKeysArePresent() {
        JsonNode json = MAPPER.valueToTree(fullyPopulatedTask());

        Set<String> actualKeys = new TreeSet<>();
        json.fieldNames().forEachRemaining(actualKeys::add);

        assertEquals(INBOX_COLUMN_NAMES, actualKeys);
    }

    @Test
    void enumValuesSerializeLowercase() {
        JsonNode json = MAPPER.valueToTree(fullyPopulatedTask());

        assertEquals("crm_deal", json.get("source_type").asText());
        assertEquals("new", json.get("status").asText());
        assertEquals("tactical", json.get("tier").asText());
        assertEquals("salesperson", json.get("role_context").asText());
        assertEquals("etl", json.get("extraction_provenance").asText());
    }

    @Test
    void deadlineAndCompletedAtSerializeAsIsoStringsNotNestedObjects() {
        JsonNode json = MAPPER.valueToTree(fullyPopulatedTask());

        assertTrue(json.get("deadline").isTextual(),
                () -> "deadline should be an ISO-8601 string, not an object: " + json.get("deadline"));
        assertEquals("2026-08-24T09:00:00Z", json.get("deadline").asText());

        assertTrue(json.get("completed_at").isTextual(),
                () -> "completed_at should be an ISO-8601 string, not an object: " + json.get("completed_at"));
        assertEquals("2026-08-23T09:00:00Z", json.get("completed_at").asText());
    }
}
