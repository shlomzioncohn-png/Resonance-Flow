package com.rnd.bff.client;

import com.rnd.bff.dto.TaskDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Client for Inbox — owns canonical task data (see
 * docs/lens-architecture-design.md). Inbox's Task API isn't live yet, so
 * this holds an in-memory mock task set instead of calling out anywhere.
 * The method signatures (userId in, task data out) are what the real HTTP
 * calls will use, so wiring them up later is a body swap, not a redesign.
 *
 * Internally, the mock is seeded as InboxTaskRecord — every field named and
 * typed after the real `tasks` table (shared/database/migrations) — and
 * only translated to TaskDTO (the BFF's frontend-facing contract) at the
 * method boundary. TaskDTO carries both: its original simplified fields
 * (status/priority/...), untouched, and the real schema fields added
 * alongside them (inboxStatus/priorityScore/tenantId/...), so Gateway and
 * lens-frontend are unaffected while the real schema is still visible (e.g.
 * in Swagger). See toTaskDTO()/toFrontendStatus() for the one place the two
 * status vocabularies don't map 1:1, and the judgment calls that involves.
 *
 * Ignores userId for now (single mock tenant) — every method still takes it
 * so callers don't change when Inbox becomes real and starts scoping by user.
 *
 * Backed by a mutable LinkedHashMap (not List.of(...) per call) so
 * updateStatus() actually persists for the lifetime of this instance —
 * getTask() after updateStatus() reflects the change, which a fresh literal
 * per call could never do.
 */
@Service
public class InboxClient {

    @Value("${inbox.service.url:http://inbox-service:8080}")
    private String inboxServiceUrl;

    // Single mock tenant/user/project ids, reused across every seeded task -
    // this mock models one tenant with one employee (Alex Morgan), same as
    // before this class existed.
    private static final String TENANT_ID = "11111111-1111-1111-1111-111111111111";
    private static final String OWNER_USER_ID = "66666666-6666-6666-6666-666666666666";
    private static final String PROJECT_PLATFORM_SERVICE = "22222222-2222-2222-2222-222222222222";
    private static final String PROJECT_CUSTOMER_SUCCESS = "33333333-3333-3333-3333-333333333333";
    private static final String PROJECT_AUTH_SERVICE = "44444444-4444-4444-4444-444444444444";
    private static final String PROJECT_BILLING = "55555555-5555-5555-5555-555555555555";

    private final Map<String, InboxTaskRecord> tasksById = new LinkedHashMap<>();

    public InboxClient() {
        seed(InboxTaskRecord.builder()
                .id("task_001")
                .tenantId(TENANT_ID)
                .projectId(PROJECT_PLATFORM_SERVICE)
                .title("Review API changes")
                .description("Review the latest API changes and update integration tests.")
                .sourceType("github_pr")
                .sourceRef("PR #4821")
                .tier("tactical")
                .roleContext("developer")
                .urgency(4)
                .effortEstimateMinutes(35)
                .effortEstimateConfidence(0.85)
                .deadline(LocalDateTime.of(2026, 8, 30, 17, 0))
                .status("approved")
                .isAvailable(true)
                .priorityScore(8.0)
                .extractionProvenance("ml_inferred")
                .confidence(0.92)
                .suggestedOwnerUserId(OWNER_USER_ID)
                .suggestedOwnerConfidence(0.90)
                .createdBy(OWNER_USER_ID)
                .createdAt(LocalDateTime.of(2026, 5, 10, 9, 15))
                .updatedAt(LocalDateTime.of(2026, 5, 10, 9, 15))
                .owner("Alex Morgan")
                .project("Platform Service")
                .build());
        seed(InboxTaskRecord.builder()
                .id("task_002")
                .tenantId(TENANT_ID)
                .projectId(PROJECT_CUSTOMER_SUCCESS)
                .title("Update customer proposal")
                .description("Incorporate legal's redlines into the Q3 proposal draft.")
                .sourceType("crm_deal")
                .sourceRef("DEAL-2291")
                .tier("strategic")
                .roleContext("developer")
                .urgency(5)
                .effortEstimateMinutes(60)
                .effortEstimateConfidence(0.70)
                .deadline(LocalDateTime.of(2026, 9, 6, 17, 0))
                .status("snoozed")
                .isAvailable(false)
                .priorityScore(8.0)
                .snoozedUntil(LocalDateTime.of(2026, 9, 10, 9, 0))
                .extractionProvenance("etl")
                .suggestedOwnerUserId(OWNER_USER_ID)
                .suggestedOwnerConfidence(0.80)
                .createdBy(OWNER_USER_ID)
                .createdAt(LocalDateTime.of(2026, 8, 20, 10, 0))
                .updatedAt(LocalDateTime.of(2026, 8, 25, 14, 0))
                .owner("Alex Morgan")
                .project("Customer Success")
                .constraints(List.of("approval_needed"))
                .build());
        seed(InboxTaskRecord.builder()
                .id("task_003")
                .tenantId(TENANT_ID)
                .projectId(PROJECT_CUSTOMER_SUCCESS)
                .title("Prepare contract draft")
                .description("Draft the renewal contract for the Acme account.")
                .sourceType("crm_deal")
                .sourceRef("DEAL-2305")
                .tier("strategic")
                .roleContext("developer")
                .urgency(3)
                .effortEstimateMinutes(90)
                .effortEstimateConfidence(0.60)
                .deadline(LocalDateTime.of(2026, 9, 7, 17, 0))
                .status("snoozed")
                .isAvailable(false)
                .priorityScore(5.0)
                .snoozedUntil(LocalDateTime.of(2026, 9, 11, 9, 0))
                .extractionProvenance("etl")
                .suggestedOwnerUserId(OWNER_USER_ID)
                .suggestedOwnerConfidence(0.75)
                .createdBy(OWNER_USER_ID)
                .createdAt(LocalDateTime.of(2026, 8, 22, 11, 0))
                .updatedAt(LocalDateTime.of(2026, 8, 27, 16, 0))
                .owner("Alex Morgan")
                .project("Customer Success")
                .constraints(List.of("waiting_for_customer"))
                .build());
        seed(InboxTaskRecord.builder()
                .id("task_004")
                .tenantId(TENANT_ID)
                .projectId(PROJECT_PLATFORM_SERVICE)
                .title("Update documentation")
                .description("Bring the API docs in line with the latest endpoint changes.")
                .sourceType("manual")
                .tier("operational")
                .roleContext("developer")
                .urgency(1)
                .effortEstimateMinutes(45)
                .deadline(LocalDateTime.of(2026, 9, 12, 17, 0))
                .status("in_progress")
                .isAvailable(true)
                .priorityScore(2.0)
                .extractionProvenance("manual")
                .createdBy(OWNER_USER_ID)
                .createdAt(LocalDateTime.of(2026, 9, 1, 9, 0))
                .updatedAt(LocalDateTime.of(2026, 9, 5, 9, 0))
                .owner("Alex Morgan")
                .project("Platform Service")
                .build());
        seed(InboxTaskRecord.builder()
                .id("task_005")
                .tenantId(TENANT_ID)
                .projectId(PROJECT_AUTH_SERVICE)
                .title("Fix login bug")
                .description("Users on Safari intermittently can't complete SSO login.")
                .sourceType("github_issue")
                .sourceRef("ISSUE-512")
                .tier("operational")
                .roleContext("developer")
                .urgency(5)
                .effortEstimateMinutes(20)
                .effortEstimateConfidence(0.80)
                .deadline(LocalDateTime.of(2026, 9, 6, 12, 0))
                .status("approved")
                .isAvailable(true)
                .priorityScore(8.0)
                .extractionProvenance("ml_inferred")
                .confidence(0.88)
                .suggestedOwnerUserId(OWNER_USER_ID)
                .suggestedOwnerConfidence(0.85)
                .createdBy(OWNER_USER_ID)
                .createdAt(LocalDateTime.of(2026, 9, 3, 8, 0))
                .updatedAt(LocalDateTime.of(2026, 9, 3, 8, 0))
                .owner("Alex Morgan")
                .project("Auth Service")
                .build());
        seed(InboxTaskRecord.builder()
                .id("task_006")
                .tenantId(TENANT_ID)
                .projectId(PROJECT_BILLING)
                .title("Write unit tests for billing")
                .description("Cover the new proration logic added last sprint.")
                .sourceType("manual")
                .tier("operational")
                .roleContext("developer")
                .urgency(3)
                .effortEstimateMinutes(50)
                .deadline(LocalDateTime.of(2026, 9, 15, 17, 0))
                .status("in_progress")
                .isAvailable(true)
                .priorityScore(5.0)
                .extractionProvenance("manual")
                .createdBy(OWNER_USER_ID)
                .createdAt(LocalDateTime.of(2026, 8, 28, 10, 0))
                .updatedAt(LocalDateTime.of(2026, 9, 2, 10, 0))
                .owner("Alex Morgan")
                .project("Billing")
                .build());
    }

    private void seed(InboxTaskRecord task) {
        tasksById.put(task.getId(), task);
    }

    // TODO: replace with GET {inboxServiceUrl}/api/v1/tasks?userId=... once Inbox exposes it
    public List<TaskDTO> getTasksForUser(String userId) {
        List<TaskDTO> result = new ArrayList<>();
        for (InboxTaskRecord record : tasksById.values()) {
            result.add(toTaskDTO(record));
        }
        return result;
    }

    // TODO: replace with GET {inboxServiceUrl}/api/v1/tasks/{taskId} once Inbox exposes it
    public TaskDTO getTaskById(String userId, String taskId) {
        InboxTaskRecord record = tasksById.get(taskId);
        return record == null ? null : toTaskDTO(record);
    }

    // TODO: replace with PATCH {inboxServiceUrl}/api/v1/tasks/{taskId} once Inbox exposes it
    public TaskDTO updateStatus(String userId, String taskId, String newStatus) {
        InboxTaskRecord record = tasksById.get(taskId);
        if (record == null) {
            return null;
        }
        // newStatus is one of TaskDTO's 4 values here (TaskStatusUpdateDTO's
        // @Pattern already rejects anything else before this is called) -
        // translate it back to the real vocabulary so the record stays a
        // consistent, real-shaped source of truth in both directions.
        record.setStatus(toInboxStatus(newStatus));
        return toTaskDTO(record);
    }

    // -------------------------------------------------------------------------
    // InboxTaskRecord (real schema shape) <-> TaskDTO (frontend contract + real fields)
    // -------------------------------------------------------------------------

    private static TaskDTO toTaskDTO(InboxTaskRecord record) {
        return TaskDTO.builder()
                .id(record.getId())
                .title(record.getTitle())
                .description(record.getDescription())
                .status(toFrontendStatus(record.getStatus()))
                .priority(toFrontendPriority(record.getPriorityScore()))
                .effortEstimate(record.getEffortEstimateMinutes())
                .dueDate(record.getDeadline())
                .owner(record.getOwner())
                .project(record.getProject())
                .dependencies(record.getDependencies())
                .constraints(record.getConstraints())
                // Real schema fields, surfaced as-is alongside their derived
                // equivalents above - see TaskDTO's javadoc.
                .tenantId(record.getTenantId())
                .projectId(record.getProjectId())
                .sourceType(record.getSourceType())
                .sourceRef(record.getSourceRef())
                .tier(record.getTier())
                .roleContext(record.getRoleContext())
                .urgency(record.getUrgency())
                .effortEstimateConfidence(record.getEffortEstimateConfidence())
                .inboxStatus(record.getStatus())
                .isAvailable(record.getIsAvailable())
                .priorityScore(record.getPriorityScore())
                .snoozedUntil(record.getSnoozedUntil())
                .extractionProvenance(record.getExtractionProvenance())
                .confidence(record.getConfidence())
                .suggestedOwnerUserId(record.getSuggestedOwnerUserId())
                .suggestedOwnerConfidence(record.getSuggestedOwnerConfidence())
                .metadata(record.getMetadata())
                .createdBy(record.getCreatedBy())
                .createdAt(record.getCreatedAt())
                .updatedAt(record.getUpdatedAt())
                .completedAt(record.getCompletedAt())
                .build();
    }

    /**
     * Real `task_status` (shared/database/migrations/V1__init_schema.sql) has
     * 8 values; TaskDTO's status has 4 (READY/IN_PROGRESS/BLOCKED/COMPLETED).
     * Two judgment calls here, neither confirmed with Inbox yet — flag both
     * if this mapping matters for a real decision:
     *
     * - There is no "blocked" value in the real enum at all. `snoozed` (a
     *   task explicitly paused, per its `snoozed_until` column) is the
     *   closest real concept to TaskDTO's BLOCKED, so it's mapped there.
     *   Chain-reported dependency blockers are a separate mechanism (see
     *   WorkspaceService, which overlays `constraints`/`dependencies`
     *   independently of status) — this mapping is only about the task's
     *   own status column.
     * - `rejected` and `cancelled` are both terminal-but-not-done states
     *   with no TaskDTO equivalent; mapped to COMPLETED (closed) rather than
     *   invented as a 5th frontend status.
     */
    private static String toFrontendStatus(String inboxStatus) {
        return switch (inboxStatus) {
            case "in_progress" -> "IN_PROGRESS";
            case "snoozed" -> "BLOCKED";
            case "done", "rejected", "cancelled" -> "COMPLETED";
            default -> "READY"; // new, in_review, approved
        };
    }

    /** Inverse of toFrontendStatus() for updateStatus() — see its javadoc. */
    private static String toInboxStatus(String frontendStatus) {
        return switch (frontendStatus) {
            case "IN_PROGRESS" -> "in_progress";
            case "BLOCKED" -> "snoozed";
            case "COMPLETED" -> "done";
            default -> "approved"; // READY
        };
    }

    /**
     * Real `priority_score` is a REAL column, 1-10 (see
     * chk_tasks_priority_score_range). TaskDTO's priority is a 3-bucket
     * LOW/MEDIUM/HIGH string. Thresholds below are an unconfirmed judgment
     * call, not a product decision — flag before relying on the exact cutoffs.
     */
    private static String toFrontendPriority(Double priorityScore) {
        if (priorityScore == null) {
            return "MEDIUM";
        }
        if (priorityScore >= 7) {
            return "HIGH";
        }
        if (priorityScore >= 4) {
            return "MEDIUM";
        }
        return "LOW";
    }
}
