package com.rnd.bff.client;

import com.rnd.bff.dto.TaskDTO;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for InboxClient's InboxTaskRecord -> TaskDTO translation layer
 * (see InboxClient's class javadoc for why that split exists). The seeded
 * values were chosen so every task's frontend-facing status/priority is
 * unchanged from before this translation layer existed - these tests pin
 * that down so a future edit to the mapping rules can't silently change
 * what the frontend sees.
 */
class InboxClientTest {

    private final InboxClient inboxClient = new InboxClient();

    @Test
    void getTasksForUser_mapsEachSeededRecordToItsExpectedFrontendShape() {
        Map<String, TaskDTO> byId =
                inboxClient.getTasksForUser("any-user").stream()
                        .collect(java.util.stream.Collectors.toMap(TaskDTO::getId, t -> t));

        assertThat(byId).hasSize(6);

        assertStatusAndPriority(byId.get("task_001"), "READY", "HIGH", "approved", 8.0);
        assertStatusAndPriority(byId.get("task_002"), "BLOCKED", "HIGH", "snoozed", 8.0);
        assertStatusAndPriority(byId.get("task_003"), "BLOCKED", "MEDIUM", "snoozed", 5.0);
        assertStatusAndPriority(byId.get("task_004"), "IN_PROGRESS", "LOW", "in_progress", 2.0);
        assertStatusAndPriority(byId.get("task_005"), "READY", "HIGH", "approved", 8.0);
        assertStatusAndPriority(byId.get("task_006"), "IN_PROGRESS", "MEDIUM", "in_progress", 5.0);
    }

    private void assertStatusAndPriority(
            TaskDTO task, String expectedStatus, String expectedPriority,
            String expectedInboxStatus, double expectedPriorityScore) {
        assertThat(task).as("task should exist").isNotNull();
        assertThat(task.getStatus()).isEqualTo(expectedStatus);
        assertThat(task.getPriority()).isEqualTo(expectedPriority);
        // The real schema-shaped fields, surfaced as-is (see TaskDTO's
        // javadoc) - pinned here so they can't silently drift from status/
        // priority, the values they're derived from.
        assertThat(task.getInboxStatus()).isEqualTo(expectedInboxStatus);
        assertThat(task.getPriorityScore()).isEqualTo(expectedPriorityScore);
    }

    @Test
    void getTaskById_populatesTheFullRealSchemaShape() {
        // Spot check on one task (not all six, and not every field on that
        // one) that the wider set of real `tasks` columns added to TaskDTO
        // actually reaches it from InboxTaskRecord, not just status/priority.
        TaskDTO task = inboxClient.getTaskById("any-user", "task_002");

        assertThat(task.getTenantId()).isEqualTo("11111111-1111-1111-1111-111111111111");
        assertThat(task.getProjectId()).isEqualTo("33333333-3333-3333-3333-333333333333");
        assertThat(task.getSourceType()).isEqualTo("crm_deal");
        assertThat(task.getSourceRef()).isEqualTo("DEAL-2291");
        assertThat(task.getTier()).isEqualTo("strategic");
        assertThat(task.getUrgency()).isEqualTo(5);
        assertThat(task.getIsAvailable()).isFalse();
        assertThat(task.getSnoozedUntil()).isNotNull();
        assertThat(task.getExtractionProvenance()).isEqualTo("etl");
        assertThat(task.getSuggestedOwnerUserId()).isEqualTo("66666666-6666-6666-6666-666666666666");
        assertThat(task.getCreatedAt()).isNotNull();
        assertThat(task.getUpdatedAt()).isNotNull();
    }

    @Test
    void getTaskById_unknownId_returnsNull() {
        assertThat(inboxClient.getTaskById("any-user", "does-not-exist")).isNull();
    }

    @Test
    void updateStatus_roundTripsAllFourFrontendValues() {
        for (String status : List.of("READY", "IN_PROGRESS", "BLOCKED", "COMPLETED")) {
            TaskDTO updated = inboxClient.updateStatus("any-user", "task_001", status);
            assertThat(updated.getStatus()).isEqualTo(status);
            // getTaskById after updateStatus reflects the same value - proves
            // the underlying InboxTaskRecord was actually mutated, not just
            // the returned copy.
            assertThat(inboxClient.getTaskById("any-user", "task_001").getStatus()).isEqualTo(status);
        }
    }

    @Test
    void updateStatus_unknownId_returnsNull() {
        assertThat(inboxClient.updateStatus("any-user", "does-not-exist", "IN_PROGRESS")).isNull();
    }
}
