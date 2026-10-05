package com.rnd.bff.client;

import com.rnd.bff.dto.TaskDTO;
import com.rnd.bff.dto.TaskListResponseDTO;
import com.rnd.bff.exception.InboxRejectedException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

        assertThat(byId).hasSize(8);

        // One task per status (8-status model), priority bucketed from priority_score:
        // >= 7 HIGH, >= 4 MEDIUM, else LOW.
        assertStatusAndPriority(byId.get("task_001"), "NEW", "HIGH", "new", 8.0);
        assertStatusAndPriority(byId.get("task_002"), "IN_REVIEW", "HIGH", "in_review", 8.0);
        assertStatusAndPriority(byId.get("task_003"), "APPROVED", "MEDIUM", "approved", 5.0);
        assertStatusAndPriority(byId.get("task_004"), "IN_PROGRESS", "LOW", "in_progress", 2.0);
        assertStatusAndPriority(byId.get("task_005"), "DONE", "HIGH", "done", 8.0);
        assertStatusAndPriority(byId.get("task_006"), "SNOOZED", "LOW", "snoozed", 3.0);
        assertStatusAndPriority(byId.get("task_007"), "REJECTED", "MEDIUM", "rejected", 4.0);
        assertStatusAndPriority(byId.get("task_008"), "CANCELLED", "LOW", "cancelled", 1.0);
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
        // task_002 is IN_REVIEW, not snoozed - snoozedUntil is only set on task_006
        assertThat(task.getSnoozedUntil()).isNull();
        assertThat(inboxClient.getTaskById("any-user", "task_006").getSnoozedUntil()).isNotNull();
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
    void updateStatus_roundTripsAllEightStatuses() {
        for (String status : List.of(
                "NEW", "IN_REVIEW", "APPROVED", "IN_PROGRESS", "SNOOZED", "DONE", "REJECTED", "CANCELLED")) {
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

    // -------------------------------------------------------------------------
    // listTasks - mock mode
    // -------------------------------------------------------------------------

    @Test
    void listTasks_mock_filtersByStatus() {
        TaskListResponseDTO result = inboxClient.listTasks("any-user", "IN_PROGRESS", null, 0, 20);

        assertThat(result.getItems()).extracting(TaskDTO::getId).containsExactly("task_004");
        assertThat(result.getTotal()).isEqualTo(1);
    }

    @Test
    void listTasks_mock_searchIsCaseInsensitiveOnTitle() {
        TaskListResponseDTO result = inboxClient.listTasks("any-user", null, "DOCUMENTATION", 0, 20);

        assertThat(result.getItems()).extracting(TaskDTO::getId).containsExactly("task_004");
    }

    @Test
    void listTasks_mock_pageBeyondLastPage_returnsEmptyItemsNotAnError() {
        TaskListResponseDTO result = inboxClient.listTasks("any-user", null, null, 5, 20);

        assertThat(result.getItems()).isEmpty();
        assertThat(result.getTotal()).isEqualTo(8);
    }

    // -------------------------------------------------------------------------
    // Real mode - against a tiny local HTTP server standing in for Inbox,
    // returning JSON in Inbox's real wire format (snake_case, UPPERCASE enums)
    // -------------------------------------------------------------------------

    private static final String ACTOR = "ef071a8a-1040-3a27-904a-f6352bad4fea";

    private static final String INBOX_TASK_JSON = """
            {"id":"539e1548-18e9-3021-92c7-25567dfe1cc8",
             "tenant":{"id":"1a2befb7-9bb3-39dd-89cd-6f1c47cd2db6","name":"Medium Org 18","slug":"medium-org-18"},
             "project_id":null,"title":"Meeting task 1","description":null,
             "source_type":"MEETING","tier":"OPERATIONAL","role_context":"DEVELOPER",
             "effort_estimate_minutes":30,"deadline":"2026-10-20T15:00:00Z",
             "status":"IN_PROGRESS","available":false,"priority_score":8.0,
             "extraction_provenance":"ML_INFERRED","created_by_id":null,
             "created_at":"2026-10-04T11:01:12.696564Z","some_future_field":"ignored"}
            """;

    private HttpServer inbox;
    private final AtomicReference<String> lastQuery = new AtomicReference<>();
    private final AtomicReference<String> lastActor = new AtomicReference<>();
    private final AtomicReference<String> lastMethod = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    @BeforeEach
    void startFakeInbox() throws IOException {
        inbox = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        inbox.createContext("/api/v1/tasks", exchange -> {
            lastQuery.set(exchange.getRequestURI().getRawQuery());
            lastActor.set(exchange.getRequestHeaders().getFirst("X-Actor-Id"));
            lastMethod.set(exchange.getRequestMethod());
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String path = exchange.getRequestURI().getPath();
            int status = 200;
            String body;
            if (path.equals("/api/v1/tasks")) {
                body = "{\"content\":[" + INBOX_TASK_JSON + "],\"page\":0,\"size\":20,"
                        + "\"total_elements\":828,\"total_pages\":42}";
            } else if (path.contains("99999999-9999-9999-9999-999999999999")) {
                status = 403;
                body = "{\"status\":403,\"error\":\"FORBIDDEN\",\"message\":\"Actor cannot act on task\"}";
            } else if (path.contains("00000000-0000-0000-0000-000000000000")) {
                status = 404;
                body = "{}";
            } else {
                body = INBOX_TASK_JSON;
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        inbox.start();
    }

    @AfterEach
    void stopFakeInbox() {
        inbox.stop(0);
    }

    private InboxClient realClient(String devActorId) {
        return new InboxClient("http://localhost:" + inbox.getAddress().getPort(), false, devActorId, 2000, 2000);
    }

    @Test
    void listTasks_real_mapsInboxPageAndSendsFiltersAndActor() {
        TaskListResponseDTO result = realClient(ACTOR).listTasks("emp_001", "IN_PROGRESS", "meeting", 0, 20);

        assertThat(lastQuery.get()).contains("status=IN_PROGRESS", "search=meeting", "page=0", "size=20");
        // Only the user's own tasks ("my tasks"), not the whole tenant
        assertThat(lastQuery.get()).contains("userId=" + ACTOR);
        // dev-actor-id replaces the Gateway's mock userId
        assertThat(lastActor.get()).isEqualTo(ACTOR);

        assertThat(result.getTotal()).isEqualTo(828);
        TaskDTO task = result.getItems().get(0);
        assertThat(task.getId()).isEqualTo("539e1548-18e9-3021-92c7-25567dfe1cc8");
        assertThat(task.getTitle()).isEqualTo("Meeting task 1");
        assertThat(task.getStatus()).isEqualTo("IN_PROGRESS");
        assertThat(task.getInboxStatus()).isEqualTo("in_progress");
        assertThat(task.getPriority()).isEqualTo("HIGH");
        assertThat(task.getEffortEstimate()).isEqualTo(30);
        assertThat(task.getDueDate()).isEqualTo(LocalDateTime.of(2026, 10, 20, 15, 0));
        assertThat(task.getTenantId()).isEqualTo("1a2befb7-9bb3-39dd-89cd-6f1c47cd2db6");
        assertThat(task.getSourceType()).isEqualTo("meeting");
        assertThat(task.getIsAvailable()).isFalse();
    }

    @Test
    void listTasks_real_withoutDevActor_sendsGatewayUserId() {
        realClient("").listTasks("emp_001", null, null, 0, 20);

        assertThat(lastActor.get()).isEqualTo("emp_001");
        assertThat(lastQuery.get()).contains("userId=emp_001");
        assertThat(lastQuery.get()).doesNotContain("status=", "search=");
    }

    @Test
    void getTaskById_real_unknownId_returnsNull() {
        assertThat(realClient(ACTOR).getTaskById("emp_001", "00000000-0000-0000-0000-000000000000")).isNull();
    }

    @Test
    void getTaskById_real_nonUuidId_returnsNullWithoutCallingInbox() {
        assertThat(realClient(ACTOR).getTaskById("emp_001", "task_001")).isNull();
        assertThat(lastMethod.get()).isNull();
    }

    @Test
    void updateStatus_real_sendsPatchWithUppercaseStatus() {
        TaskDTO updated = realClient(ACTOR).updateStatus("emp_001", "539e1548-18e9-3021-92c7-25567dfe1cc8", "in_progress");

        assertThat(lastMethod.get()).isEqualTo("PATCH");
        assertThat(lastBody.get()).isEqualTo("{\"status\":\"IN_PROGRESS\"}");
        assertThat(updated.getStatus()).isEqualTo("IN_PROGRESS");
    }

    @Test
    void getTaskById_real_inboxForbidden_throwsInboxRejectedWithInboxMessage() {
        assertThatThrownBy(() -> realClient(ACTOR).getTaskById("emp_001", "99999999-9999-9999-9999-999999999999"))
                .isInstanceOf(InboxRejectedException.class)
                .hasMessage("Actor cannot act on task")
                .satisfies(ex -> assertThat(((InboxRejectedException) ex).getStatus()).isEqualTo(403));
    }
}
