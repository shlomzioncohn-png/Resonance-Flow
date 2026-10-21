package com.rnd.bridge.task;

import com.google.protobuf.InvalidProtocolBufferException;
import com.rnd.bridge.core.error.ValidationException;
import com.rnd.bridge.task.model.ExtractionProvenance;
import com.rnd.bridge.task.model.RoleContext;
import com.rnd.bridge.task.model.SourceType;
import com.rnd.bridge.task.model.Task;
import com.rnd.bridge.task.model.TaskStatus;
import com.rnd.bridge.task.model.Tier;
import com.rnd.inbox.event.StructuredTaskEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link StructuredTaskEventMapper} field by field against the item-6 table: which
 * fields are always set, which are proto3 {@code optional} (set only on a non-null {@link Task}
 * value, zero/empty preserved rather than treated as "unset"), the fail-fast validation that
 * keeps a malformed {@link Task} from ever reaching Inbox, and exhaustive coverage of every enum
 * value on both sides of the Bridge <-> proto mapping.
 */
class StructuredTaskEventMapperTest {

    private static final String VALID_TENANT_ID = "11111111-1111-1111-1111-111111111111";

    private static Task minimalTask() {
        return new Task(
                VALID_TENANT_ID, null, "Title", null, null, null, null, null, null, null,
                SourceType.GITHUB_ISSUE, "ref-1", null, null, null, null);
    }

    private static Task fullyPopulatedTask() {
        return new Task(
                VALID_TENANT_ID, "project-1", "Full title", "Full description", TaskStatus.IN_PROGRESS,
                3, 0.75, Tier.TACTICAL, ExtractionProvenance.ETL, RoleContext.DEVELOPER,
                SourceType.JIRA_ISSUE, "ref-2", 45,
                Instant.ofEpochSecond(1_700_000_000L, 123_456_789L), null, null);
    }

    // ---- always-set fields ----

    @Test
    void eventId_alwaysSetToFreshUuidPerCall() {
        StructuredTaskEvent first = StructuredTaskEventMapper.from(minimalTask());
        StructuredTaskEvent second = StructuredTaskEventMapper.from(minimalTask());

        assertTrue(first.hasEventId());
        assertDoesNotThrow(() -> UUID.fromString(first.getEventId()));
        assertNotEquals(first.getEventId(), second.getEventId());
    }

    @Test
    void tenantId_setFromTaskOrganizationId() {
        assertEquals(VALID_TENANT_ID, StructuredTaskEventMapper.from(minimalTask()).getTenantId());
    }

    @Test
    void title_alwaysSet() {
        assertEquals("Title", StructuredTaskEventMapper.from(minimalTask()).getTitle());
    }

    @Test
    void sourceRef_alwaysSet() {
        assertEquals("ref-1", StructuredTaskEventMapper.from(minimalTask()).getSourceRef());
    }

    @Test
    void sourceType_alwaysSet() {
        assertEquals(com.rnd.inbox.event.SourceType.SOURCE_TYPE_GITHUB_ISSUE,
                StructuredTaskEventMapper.from(minimalTask()).getSourceType());
    }

    // ---- optional scalar/message fields: unset on null, set (incl. zero/empty) on non-null ----

    @Test
    void projectId_unsetWhenTaskValueIsNull() {
        assertFalse(StructuredTaskEventMapper.from(minimalTask()).hasProjectId());
    }

    @Test
    void projectId_setWhenTaskValueIsNonNull() {
        StructuredTaskEvent event = StructuredTaskEventMapper.from(fullyPopulatedTask());
        assertTrue(event.hasProjectId());
        assertEquals("project-1", event.getProjectId());
    }

    @Test
    void description_unsetWhenTaskValueIsNull() {
        assertFalse(StructuredTaskEventMapper.from(minimalTask()).hasDescription());
    }

    @Test
    void description_setWhenTaskValueIsNonNull() {
        StructuredTaskEvent event = StructuredTaskEventMapper.from(fullyPopulatedTask());
        assertTrue(event.hasDescription());
        assertEquals("Full description", event.getDescription());
    }

    @Test
    void tier_unsetWhenTaskValueIsNull() {
        assertFalse(StructuredTaskEventMapper.from(minimalTask()).hasTier());
    }

    @Test
    void tier_setWhenTaskValueIsNonNull() {
        StructuredTaskEvent event = StructuredTaskEventMapper.from(fullyPopulatedTask());
        assertTrue(event.hasTier());
        assertEquals(com.rnd.inbox.event.TaskTier.TASK_TIER_TACTICAL, event.getTier());
    }

    @Test
    void roleContext_unsetWhenTaskValueIsNull() {
        assertFalse(StructuredTaskEventMapper.from(minimalTask()).hasRoleContext());
    }

    @Test
    void roleContext_setWhenTaskValueIsNonNull() {
        StructuredTaskEvent event = StructuredTaskEventMapper.from(fullyPopulatedTask());
        assertTrue(event.hasRoleContext());
        assertEquals(com.rnd.inbox.event.TaskRoleContext.TASK_ROLE_CONTEXT_DEVELOPER, event.getRoleContext());
    }

    @Test
    void urgency_unsetWhenTaskValueIsNull() {
        assertFalse(StructuredTaskEventMapper.from(minimalTask()).hasUrgency());
    }

    @Test
    void urgency_setWhenTaskValueIsNonNull() {
        StructuredTaskEvent event = StructuredTaskEventMapper.from(fullyPopulatedTask());
        assertTrue(event.hasUrgency());
        assertEquals(3, event.getUrgency());
    }

    @Test
    void urgency_zeroIsPreservedWhenActuallyZero_notTreatedAsUnset() {
        Task task = new Task(
                VALID_TENANT_ID, null, "Title", null, null, 0, null, null, null, null,
                SourceType.GITHUB_ISSUE, "ref-1", null, null, null, null);

        StructuredTaskEvent event = StructuredTaskEventMapper.from(task);

        assertTrue(event.hasUrgency());
        assertEquals(0, event.getUrgency());
    }

    @Test
    void effortEstimateMinutes_unsetWhenTaskValueIsNull() {
        assertFalse(StructuredTaskEventMapper.from(minimalTask()).hasEffortEstimateMinutes());
    }

    @Test
    void effortEstimateMinutes_setWhenTaskValueIsNonNull() {
        StructuredTaskEvent event = StructuredTaskEventMapper.from(fullyPopulatedTask());
        assertTrue(event.hasEffortEstimateMinutes());
        assertEquals(45, event.getEffortEstimateMinutes());
    }

    @Test
    void effortEstimateMinutes_zeroIsPreservedWhenActuallyZero_notTreatedAsUnset() {
        Task task = new Task(
                VALID_TENANT_ID, null, "Title", null, null, null, null, null, null, null,
                SourceType.GITHUB_ISSUE, "ref-1", 0, null, null, null);

        StructuredTaskEvent event = StructuredTaskEventMapper.from(task);

        assertTrue(event.hasEffortEstimateMinutes());
        assertEquals(0, event.getEffortEstimateMinutes());
    }

    @Test
    void deadline_unsetWhenTaskValueIsNull() {
        assertFalse(StructuredTaskEventMapper.from(minimalTask()).hasDeadline());
    }

    @Test
    void deadline_setWithFullSecondsAndNanosPrecisionWhenTaskValueIsNonNull() {
        Instant deadline = Instant.ofEpochSecond(1_700_000_000L, 123_456_789L);
        Task task = new Task(
                VALID_TENANT_ID, null, "Title", null, null, null, null, null, null, null,
                SourceType.GITHUB_ISSUE, "ref-1", null, deadline, null, null);

        StructuredTaskEvent event = StructuredTaskEventMapper.from(task);

        assertTrue(event.hasDeadline());
        assertEquals(1_700_000_000L, event.getDeadline().getSeconds());
        assertEquals(123_456_789, event.getDeadline().getNanos());
    }

    @Test
    void status_unsetWhenTaskValueIsNull() {
        assertFalse(StructuredTaskEventMapper.from(minimalTask()).hasStatus());
    }

    @Test
    void status_setWhenTaskValueIsNonNull() {
        StructuredTaskEvent event = StructuredTaskEventMapper.from(fullyPopulatedTask());
        assertTrue(event.hasStatus());
        assertEquals(com.rnd.inbox.event.TaskStatus.TASK_STATUS_IN_PROGRESS, event.getStatus());
    }

    @Test
    void confidence_unsetWhenTaskValueIsNull() {
        assertFalse(StructuredTaskEventMapper.from(minimalTask()).hasConfidence());
    }

    @Test
    void confidence_setWhenTaskValueIsNonNull() {
        StructuredTaskEvent event = StructuredTaskEventMapper.from(fullyPopulatedTask());
        assertTrue(event.hasConfidence());
        assertEquals(0.75, event.getConfidence());
    }

    @Test
    void confidence_zeroIsPreservedWhenActuallyZero_notTreatedAsUnset() {
        Task task = new Task(
                VALID_TENANT_ID, null, "Title", null, null, null, 0.0, null, null, null,
                SourceType.GITHUB_ISSUE, "ref-1", null, null, null, null);

        StructuredTaskEvent event = StructuredTaskEventMapper.from(task);

        assertTrue(event.hasConfidence());
        assertEquals(0.0, event.getConfidence());
    }

    // ---- fields Bridge has no source for ----

    @Test
    void createdByUserId_alwaysLeftUnset() {
        assertFalse(StructuredTaskEventMapper.from(fullyPopulatedTask()).hasCreatedByUserId());
    }

    @Test
    void assignees_alwaysLeftEmpty() {
        assertTrue(StructuredTaskEventMapper.from(fullyPopulatedTask()).getAssigneesList().isEmpty());
    }

    // ---- fail-fast validation: never build a message Inbox would dead-letter ----

    @Test
    void failsFast_whenTenantIdIsNull() {
        Task task = new Task(
                null, null, "Title", null, null, null, null, null, null, null,
                SourceType.GITHUB_ISSUE, "ref-1", null, null, null, null);

        ValidationException ex = assertThrows(ValidationException.class, () -> StructuredTaskEventMapper.from(task));
        assertTrue(ex.getMessage().contains("organizationId"));
    }

    @Test
    void failsFast_whenTenantIdIsBlank() {
        Task task = new Task(
                "   ", null, "Title", null, null, null, null, null, null, null,
                SourceType.GITHUB_ISSUE, "ref-1", null, null, null, null);

        assertThrows(ValidationException.class, () -> StructuredTaskEventMapper.from(task));
    }

    @Test
    void failsFast_whenTenantIdIsNotAValidUuid() {
        Task task = new Task(
                "not-a-uuid", null, "Title", null, null, null, null, null, null, null,
                SourceType.GITHUB_ISSUE, "ref-1", null, null, null, null);

        ValidationException ex = assertThrows(ValidationException.class, () -> StructuredTaskEventMapper.from(task));
        assertTrue(ex.getMessage().contains("organizationId"));
    }

    @Test
    void failsFast_whenTenantIdIsLenientForUuidFromStringButNotInboxsCanonicalShape() {
        // UUID.fromString("1-1-1-1-1") actually succeeds (Java's parser only needs 5
        // hyphen-separated hex groups, not fixed lengths) - but Inbox's own @ValidUuid regex
        // (8-4-4-4-12) rejects it. This is exactly the gap the mapper must not reproduce.
        assertDoesNotThrow(() -> UUID.fromString("1-1-1-1-1"));
        Task task = new Task(
                "1-1-1-1-1", null, "Title", null, null, null, null, null, null, null,
                SourceType.GITHUB_ISSUE, "ref-1", null, null, null, null);

        ValidationException ex = assertThrows(ValidationException.class, () -> StructuredTaskEventMapper.from(task));
        assertTrue(ex.getMessage().contains("organizationId"));
    }

    @Test
    void failsFast_whenTenantIdGroupLengthsAreWrong() {
        // One extra hex digit in the first group (9, not 8) - still 5 hyphen-separated groups,
        // still rejected by Inbox's fixed-length regex.
        Task task = new Task(
                "111111111-1111-1111-1111-111111111111", null, "Title", null, null, null, null, null,
                null, null, SourceType.GITHUB_ISSUE, "ref-1", null, null, null, null);

        assertThrows(ValidationException.class, () -> StructuredTaskEventMapper.from(task));
    }

    @Test
    void tenantId_acceptsCanonicalLowercaseUuid() {
        Task task = new Task(
                "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", null, "Title", null, null, null, null, null,
                null, null, SourceType.GITHUB_ISSUE, "ref-1", null, null, null, null);

        assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                StructuredTaskEventMapper.from(task).getTenantId());
    }

    @Test
    void tenantId_acceptsCanonicalUppercaseUuid_matchingInboxsCaseInsensitiveRule() {
        Task task = new Task(
                "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE", null, "Title", null, null, null, null, null,
                null, null, SourceType.GITHUB_ISSUE, "ref-1", null, null, null, null);

        assertEquals("AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE",
                StructuredTaskEventMapper.from(task).getTenantId());
    }

    @Test
    void failsFast_whenTitleIsNull() {
        Task task = new Task(
                VALID_TENANT_ID, null, null, null, null, null, null, null, null, null,
                SourceType.GITHUB_ISSUE, "ref-1", null, null, null, null);

        ValidationException ex = assertThrows(ValidationException.class, () -> StructuredTaskEventMapper.from(task));
        assertTrue(ex.getMessage().contains("title"));
    }

    @Test
    void failsFast_whenTitleIsBlank() {
        Task task = new Task(
                VALID_TENANT_ID, null, "   ", null, null, null, null, null, null, null,
                SourceType.GITHUB_ISSUE, "ref-1", null, null, null, null);

        assertThrows(ValidationException.class, () -> StructuredTaskEventMapper.from(task));
    }

    @Test
    void failsFast_whenSourceRefIsNull() {
        Task task = new Task(
                VALID_TENANT_ID, null, "Title", null, null, null, null, null, null, null,
                SourceType.GITHUB_ISSUE, null, null, null, null, null);

        ValidationException ex = assertThrows(ValidationException.class, () -> StructuredTaskEventMapper.from(task));
        assertTrue(ex.getMessage().contains("sourceRef"));
    }

    @Test
    void failsFast_whenSourceRefIsBlank() {
        Task task = new Task(
                VALID_TENANT_ID, null, "Title", null, null, null, null, null, null, null,
                SourceType.GITHUB_ISSUE, "   ", null, null, null, null);

        assertThrows(ValidationException.class, () -> StructuredTaskEventMapper.from(task));
    }

    @Test
    void failsFast_whenSourceTypeIsNull() {
        Task task = new Task(
                VALID_TENANT_ID, null, "Title", null, null, null, null, null, null, null,
                null, "ref-1", null, null, null, null);

        ValidationException ex = assertThrows(ValidationException.class, () -> StructuredTaskEventMapper.from(task));
        assertTrue(ex.getMessage().contains("sourceType"));
    }

    // ---- exhaustive enum coverage: every Bridge value maps correctly, every reachable proto
    //      value matches, and every non-reachable proto value is explicitly documented ----

    private static <P extends Enum<P>> void assertEveryProtoValueReachableOrDocumented(
            P[] allProtoValues, P unspecified, P unrecognized, Set<P> reachableFromBridge, Set<P> documentedUnused) {
        for (P protoValue : allProtoValues) {
            if (protoValue == unspecified || protoValue == unrecognized) {
                continue;
            }
            assertTrue(reachableFromBridge.contains(protoValue) || documentedUnused.contains(protoValue),
                    () -> protoValue + " is neither mapped from any Bridge value nor documented as unused by "
                            + "Bridge - a value was added/renamed on one side without the other being updated");
        }
    }

    // SourceType

    private static final Map<SourceType, com.rnd.inbox.event.SourceType> EXPECTED_SOURCE_TYPE = Map.ofEntries(
            Map.entry(SourceType.GITHUB_ISSUE, com.rnd.inbox.event.SourceType.SOURCE_TYPE_GITHUB_ISSUE),
            Map.entry(SourceType.GITHUB_PR, com.rnd.inbox.event.SourceType.SOURCE_TYPE_GITHUB_PR),
            Map.entry(SourceType.JIRA_ISSUE, com.rnd.inbox.event.SourceType.SOURCE_TYPE_JIRA_ISSUE),
            Map.entry(SourceType.CRM_DEAL, com.rnd.inbox.event.SourceType.SOURCE_TYPE_CRM_DEAL),
            Map.entry(SourceType.EMAIL, com.rnd.inbox.event.SourceType.SOURCE_TYPE_EMAIL),
            Map.entry(SourceType.SLACK, com.rnd.inbox.event.SourceType.SOURCE_TYPE_SLACK),
            Map.entry(SourceType.WHATSAPP, com.rnd.inbox.event.SourceType.SOURCE_TYPE_WHATSAPP),
            Map.entry(SourceType.MEETING, com.rnd.inbox.event.SourceType.SOURCE_TYPE_MEETING),
            Map.entry(SourceType.DOC, com.rnd.inbox.event.SourceType.SOURCE_TYPE_DOC),
            Map.entry(SourceType.SCANNED_DOC, com.rnd.inbox.event.SourceType.SOURCE_TYPE_SCANNED_DOC),
            Map.entry(SourceType.CALENDAR, com.rnd.inbox.event.SourceType.SOURCE_TYPE_CALENDAR));

    // Inbox-only values: MANUAL means "typed directly into the product, no adapter involved" -
    // bypasses Bridge entirely. DRIVE has no Bridge lane today. ZOOM and BIGBLUEBUTTON (added by
    // Inbox, #749): Bridge hands meetings off as RawObjectEvent, never as a structured Task.
    // See teams/bridge/CLAUDE.md §6.
    private static final Set<com.rnd.inbox.event.SourceType> SOURCE_TYPE_NOT_SENT_BY_BRIDGE = Set.of(
            com.rnd.inbox.event.SourceType.SOURCE_TYPE_MANUAL,
            com.rnd.inbox.event.SourceType.SOURCE_TYPE_DRIVE,
            com.rnd.inbox.event.SourceType.SOURCE_TYPE_ZOOM,
            com.rnd.inbox.event.SourceType.SOURCE_TYPE_BIGBLUEBUTTON);

    @ParameterizedTest
    @EnumSource(SourceType.class)
    void everyBridgeSourceTypeValueMapsToItsExpectedProtoValue(SourceType bridgeValue) {
        Task task = new Task(
                VALID_TENANT_ID, null, "Title", null, null, null, null, null, null, null,
                bridgeValue, "ref-1", null, null, null, null);

        assertEquals(EXPECTED_SOURCE_TYPE.get(bridgeValue), StructuredTaskEventMapper.from(task).getSourceType());
    }

    @Test
    void expectedSourceTypeMapCoversEveryBridgeValue() {
        // Guards the fixture itself, not just the mapper: a Bridge value missing from the map
        // would make the parameterized test above NPE rather than fail with a clear message.
        assertEquals(EnumSet.allOf(SourceType.class), EXPECTED_SOURCE_TYPE.keySet());
    }

    @Test
    void everyProtoSourceTypeValueIsReachableFromBridgeOrDocumentedAsUnused() {
        assertEveryProtoValueReachableOrDocumented(
                com.rnd.inbox.event.SourceType.values(),
                com.rnd.inbox.event.SourceType.SOURCE_TYPE_UNSPECIFIED,
                com.rnd.inbox.event.SourceType.UNRECOGNIZED,
                EnumSet.copyOf(EXPECTED_SOURCE_TYPE.values()),
                SOURCE_TYPE_NOT_SENT_BY_BRIDGE);
    }

    // Tier

    private static final Map<Tier, com.rnd.inbox.event.TaskTier> EXPECTED_TIER = Map.of(
            Tier.OPERATIONAL, com.rnd.inbox.event.TaskTier.TASK_TIER_OPERATIONAL,
            Tier.TACTICAL, com.rnd.inbox.event.TaskTier.TASK_TIER_TACTICAL,
            Tier.STRATEGIC, com.rnd.inbox.event.TaskTier.TASK_TIER_STRATEGIC);

    @ParameterizedTest
    @EnumSource(Tier.class)
    void everyBridgeTierValueMapsToItsExpectedProtoValue(Tier bridgeValue) {
        Task task = new Task(
                VALID_TENANT_ID, null, "Title", null, null, null, null, bridgeValue, null, null,
                SourceType.GITHUB_ISSUE, "ref-1", null, null, null, null);

        assertEquals(EXPECTED_TIER.get(bridgeValue), StructuredTaskEventMapper.from(task).getTier());
    }

    @Test
    void expectedTierMapCoversEveryBridgeValue() {
        assertEquals(EnumSet.allOf(Tier.class), EXPECTED_TIER.keySet());
    }

    @Test
    void everyProtoTierValueIsReachableFromBridge() {
        assertEveryProtoValueReachableOrDocumented(
                com.rnd.inbox.event.TaskTier.values(),
                com.rnd.inbox.event.TaskTier.TASK_TIER_UNSPECIFIED,
                com.rnd.inbox.event.TaskTier.UNRECOGNIZED,
                EnumSet.copyOf(EXPECTED_TIER.values()),
                Set.of());
    }

    // RoleContext

    private static final Map<RoleContext, com.rnd.inbox.event.TaskRoleContext> EXPECTED_ROLE_CONTEXT = Map.of(
            RoleContext.DEVELOPER, com.rnd.inbox.event.TaskRoleContext.TASK_ROLE_CONTEXT_DEVELOPER,
            RoleContext.MANAGER, com.rnd.inbox.event.TaskRoleContext.TASK_ROLE_CONTEXT_MANAGER,
            RoleContext.HIGHER_MANAGER, com.rnd.inbox.event.TaskRoleContext.TASK_ROLE_CONTEXT_HIGHER_MANAGER,
            RoleContext.FIELD_ENGINEER, com.rnd.inbox.event.TaskRoleContext.TASK_ROLE_CONTEXT_FIELD_ENGINEER,
            RoleContext.SALESPERSON, com.rnd.inbox.event.TaskRoleContext.TASK_ROLE_CONTEXT_SALESPERSON,
            RoleContext.CEO, com.rnd.inbox.event.TaskRoleContext.TASK_ROLE_CONTEXT_CEO);

    @ParameterizedTest
    @EnumSource(RoleContext.class)
    void everyBridgeRoleContextValueMapsToItsExpectedProtoValue(RoleContext bridgeValue) {
        Task task = new Task(
                VALID_TENANT_ID, null, "Title", null, null, null, null, null, null, bridgeValue,
                SourceType.GITHUB_ISSUE, "ref-1", null, null, null, null);

        assertEquals(EXPECTED_ROLE_CONTEXT.get(bridgeValue), StructuredTaskEventMapper.from(task).getRoleContext());
    }

    @Test
    void expectedRoleContextMapCoversEveryBridgeValue() {
        assertEquals(EnumSet.allOf(RoleContext.class), EXPECTED_ROLE_CONTEXT.keySet());
    }

    @Test
    void everyProtoRoleContextValueIsReachableFromBridge() {
        assertEveryProtoValueReachableOrDocumented(
                com.rnd.inbox.event.TaskRoleContext.values(),
                com.rnd.inbox.event.TaskRoleContext.TASK_ROLE_CONTEXT_UNSPECIFIED,
                com.rnd.inbox.event.TaskRoleContext.UNRECOGNIZED,
                EnumSet.copyOf(EXPECTED_ROLE_CONTEXT.values()),
                Set.of());
    }

    // TaskStatus

    private static final Map<TaskStatus, com.rnd.inbox.event.TaskStatus> EXPECTED_STATUS = Map.of(
            TaskStatus.NEW, com.rnd.inbox.event.TaskStatus.TASK_STATUS_NEW,
            TaskStatus.IN_REVIEW, com.rnd.inbox.event.TaskStatus.TASK_STATUS_IN_REVIEW,
            TaskStatus.APPROVED, com.rnd.inbox.event.TaskStatus.TASK_STATUS_APPROVED,
            TaskStatus.IN_PROGRESS, com.rnd.inbox.event.TaskStatus.TASK_STATUS_IN_PROGRESS,
            TaskStatus.SNOOZED, com.rnd.inbox.event.TaskStatus.TASK_STATUS_SNOOZED,
            TaskStatus.DONE, com.rnd.inbox.event.TaskStatus.TASK_STATUS_DONE,
            TaskStatus.REJECTED, com.rnd.inbox.event.TaskStatus.TASK_STATUS_REJECTED,
            TaskStatus.CANCELLED, com.rnd.inbox.event.TaskStatus.TASK_STATUS_CANCELLED);

    @ParameterizedTest
    @EnumSource(TaskStatus.class)
    void everyBridgeTaskStatusValueMapsToItsExpectedProtoValue(TaskStatus bridgeValue) {
        Task task = new Task(
                VALID_TENANT_ID, null, "Title", null, bridgeValue, null, null, null, null, null,
                SourceType.GITHUB_ISSUE, "ref-1", null, null, null, null);

        assertEquals(EXPECTED_STATUS.get(bridgeValue), StructuredTaskEventMapper.from(task).getStatus());
    }

    @Test
    void expectedStatusMapCoversEveryBridgeValue() {
        assertEquals(EnumSet.allOf(TaskStatus.class), EXPECTED_STATUS.keySet());
    }

    @Test
    void everyProtoTaskStatusValueIsReachableFromBridge() {
        assertEveryProtoValueReachableOrDocumented(
                com.rnd.inbox.event.TaskStatus.values(),
                com.rnd.inbox.event.TaskStatus.TASK_STATUS_UNSPECIFIED,
                com.rnd.inbox.event.TaskStatus.UNRECOGNIZED,
                EnumSet.copyOf(EXPECTED_STATUS.values()),
                Set.of());
    }

    // ---- round-trip ----

    @Test
    void roundTrip_serializeThenParse_producesAnEqualMessage() throws InvalidProtocolBufferException {
        StructuredTaskEvent original = StructuredTaskEventMapper.from(fullyPopulatedTask());

        byte[] bytes = original.toByteArray();
        StructuredTaskEvent parsed = StructuredTaskEvent.parseFrom(bytes);

        assertEquals(original, parsed);
    }
}
