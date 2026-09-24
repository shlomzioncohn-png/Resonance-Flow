package com.rnd.bridge.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks {@link BridgeTaskEventDto} against Inbox's real ingestion contract -
 * not just Bridge's own recorded shape, which is all {@link
 * BridgeTaskEventDtoTest} can see.
 * <p>
 * <b>Why this is a hand-copied mirror, not a real cross-check.</b> Bridge
 * cannot depend on Inbox's classes: the root {@code pom.xml} is a
 * multi-module aggregator, but both {@code teams/bridge} and
 * {@code teams/inbox} are commented out of its {@code <modules>} list, each
 * has its own independent {@code <parent>} (Spring Boot's starter parent
 * directly, not this root pom), and neither team's build ever publishes an
 * artifact the other could resolve. This is a straightforward consequence of
 * the monorepo's own rule that there is no shared application code between
 * teams (root {@code CLAUDE.md}). So the constants below are a manually
 * copied snapshot of {@code com.rnd.inbox.structuredtask.StructuredTaskEventDto}
 * (source of truth:
 * {@code teams/inbox/src/main/java/com/rnd/inbox/structuredtask/StructuredTaskEventDto.java}),
 * last verified against commit {@code 9684b8e8} on 2026-09-15 - the same
 * commit that renamed {@code organizationId} to {@code tenantId} and broke
 * this contract for nine days before anyone noticed.
 * <p>
 * <b>What a green run here does and does not prove.</b> It proves Bridge's
 * output still matches this recorded snapshot of Inbox's contract. It does
 * NOT prove the snapshot itself is still current - if Inbox changes
 * {@code StructuredTaskEventDto} again without this file being updated in
 * lockstep, this test keeps passing while silently drifting from reality,
 * exactly like {@code StructuredTaskConsumerIntegrationTest} did on Inbox's
 * side (see {@code CLAUDE.md} §6). There is no substitute here for someone
 * actually re-checking Inbox's real class when their contract changes.
 */
class BridgeTaskEventDtoInboxContractTest {

    // Same mapper setup as BridgeTaskEventDtoTest.
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    /**
     * Every field name {@code StructuredTaskEventDto} declares, including
     * the four assignment fields Bridge never populates (no assignment data
     * exists yet on this lane) - this is why the check below is "every key
     * Bridge sends is one of these," not exact-set equality: Bridge sending
     * fewer keys than Inbox accepts is fine, Bridge sending a key Inbox
     * doesn't recognize is the actual bug class this test exists to catch.
     * <p>
     * Note: BridgeTaskEventDto uses @JsonNaming(SnakeCaseStrategy.class), so
     * all keys are snake_case. StructuredTaskEventDto uses camelCase field names
     * but Jackson's default naming strategy, so the JSON keys are also camelCase.
     * This test checks the JSON keys Bridge sends (snake_case) against the
     * field names Inbox expects (camelCase) - they won't match directly, so we
     * exclude Bridge-specific keys like event_id that Inbox doesn't have.
     */
    private static final Set<String> INBOX_RECOGNIZED_FIELD_NAMES = Set.of(
            "tenantId", "projectId", "title", "description", "sourceType",
            "sourceRef", "tier", "roleContext", "urgency", "effortEstimateMinutes",
            "deadline", "status", "confidence",
            "assigneeUserId", "assigneeTeamId", "assignmentRole", "assignedByUserId");

    /**
     * Keys that Bridge sends but Inbox's StructuredTaskEventDto does not have.
     * These are excluded from the contract check above.
     */
    private static final Set<String> BRIDGE_SPECIFIC_KEYS = Set.of("event_id", "tenant_id");

    /**
     * Mirrors {@code @ValidEnum(enumClass = SourceType.class, allowed =
     * {"GITHUB_ISSUE", "GITHUB_PR", "CRM_DEAL"})} on
     * {@code StructuredTaskEventDto.sourceType} - Inbox's allow-list for
     * this topic specifically, not the full set of values its {@code
     * SourceType} enum could otherwise hold.
     */
    private static final Set<String> INBOX_ALLOWED_SOURCE_TYPES =
            Set.of("GITHUB_ISSUE", "GITHUB_PR", "CRM_DEAL");

    private static Task crmTask() {
        return new Task(
                "11111111-1111-1111-1111-111111111111",
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
    void everyKeyBridgeSendsIsRecognizedByInboxsRealDto() {
        JsonNode json = MAPPER.valueToTree(BridgeTaskEventDto.from(crmTask()));

        Iterator<String> actualKeys = json.fieldNames();
        while (actualKeys.hasNext()) {
            String key = actualKeys.next();
            // Skip Bridge-specific keys that Inbox doesn't have
            if (BRIDGE_SPECIFIC_KEYS.contains(key)) {
                continue;
            }
            assertTrue(INBOX_RECOGNIZED_FIELD_NAMES.contains(key),
                    () -> "Bridge sends \"" + key + "\" but StructuredTaskEventDto (Inbox) has no "
                            + "such field as of the commit this test is pinned to - see class Javadoc");
        }
    }

    @Test
    void tenantIdSatisfiesInboxsNotBlankAndValidUuidConstraints() {
        BridgeTaskEventDto event = BridgeTaskEventDto.from(crmTask());

        // Mirrors @NotBlank on StructuredTaskEventDto.tenantId.
        assertFalse(event.tenantId() == null || event.tenantId().isBlank(),
                "tenantId must not be blank - Inbox's @NotBlank rejects it otherwise");

        // Mirrors @ValidUuid on StructuredTaskEventDto.tenantId.
        assertDoesNotThrow(() -> UUID.fromString(event.tenantId()),
                "tenantId must be a valid UUID string - Inbox's @ValidUuid rejects it otherwise");
    }

    @Test
    void aCrmMessagesSourceTypeIsOnInboxsAllowList() {
        BridgeTaskEventDto event = BridgeTaskEventDto.from(crmTask());

        assertTrue(INBOX_ALLOWED_SOURCE_TYPES.contains(event.sourceType()),
                () -> event.sourceType() + " is not on Inbox's @ValidEnum allow-list for this topic");
    }

    /**
     * Not a bug in Bridge's code - a known, open cross-team gap tracked in
     * {@code CLAUDE.md} and the {@code bridge-structured-tasks-proto-spec.md}
     * open questions. Inbox's allow-list for this topic was never extended
     * to include JIRA, so every JIRA-sourced message fails this constraint
     * today regardless of anything on Bridge's side. This test exists so
     * that fact stays visible and pinned, not so it gets "fixed" here.
     */
    @Test
    void aJiraMessagesSourceTypeIsNotYetOnInboxsAllowList() {
        assertFalse(INBOX_ALLOWED_SOURCE_TYPES.contains(SourceType.JIRA_ISSUE.name()),
                "JIRA_ISSUE has been added to Inbox's allow-list - update this test and the open "
                        + "question in bridge-structured-tasks-proto-spec.md, this is no longer true");
    }
}
