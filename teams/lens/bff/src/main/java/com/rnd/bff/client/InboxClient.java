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
 * method boundary. TaskDTO carries both the real schema fields and is
 * designed to surface the full 8-value status enum directly, with no
 * simplification or mapping. This ensures the API is a transparent window
 * into the canonical data model (see docs/employee-workspace-api-contract.md
 * §5.0 for the status model change).
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
        // task_001: NEW status, high priority, available
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
                .status("new")
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
        // task_002: IN_REVIEW status, snoozed/unavailable
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
                .status("in_review")
                .isAvailable(false)
                .priorityScore(8.0)
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
        // task_003: APPROVED status, snoozed/unavailable with deadline
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
                .status("approved")
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
        // task_004: IN_PROGRESS status, available
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
        // task_005: DONE status (completed), available
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
                .status("done")
                .isAvailable(true)
                .priorityScore(8.0)
                .extractionProvenance("ml_inferred")
                .confidence(0.88)
                .suggestedOwnerUserId(OWNER_USER_ID)
                .suggestedOwnerConfidence(0.85)
                .createdBy(OWNER_USER_ID)
                .createdAt(LocalDateTime.of(2026, 9, 3, 8, 0))
                .updatedAt(LocalDateTime.of(2026, 9, 3, 8, 0))
                .completedAt(LocalDateTime.of(2026, 9, 5, 14, 30))
                .owner("Alex Morgan")
                .project("Auth Service")
                .build());
        // task_006: SNOOZED status (explicitly paused), unavailable
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
                .status("snoozed")
                .isAvailable(false)
                .priorityScore(3.0)
                .snoozedUntil(LocalDateTime.of(2026, 9, 18, 9, 0))
                .extractionProvenance("manual")
                .createdBy(OWNER_USER_ID)
                .createdAt(LocalDateTime.of(2026, 8, 28, 10, 0))
                .updatedAt(LocalDateTime.of(2026, 9, 2, 10, 0))
                .owner("Alex Morgan")
                .project("Billing")
                .build());
        // task_007: REJECTED status (not approved), unavailable
        seed(InboxTaskRecord.builder()
                .id("task_007")
                .tenantId(TENANT_ID)
                .projectId(PROJECT_PLATFORM_SERVICE)
                .title("Implement new caching layer")
                .description("Add Redis caching to improve API response times.")
                .sourceType("github_issue")
                .sourceRef("ISSUE-445")
                .tier("tactical")
                .roleContext("developer")
                .urgency(2)
                .effortEstimateMinutes(120)
                .effortEstimateConfidence(0.75)
                .deadline(LocalDateTime.of(2026, 10, 1, 17, 0))
                .status("rejected")
                .isAvailable(false)
                .priorityScore(4.0)
                .extractionProvenance("ml_inferred")
                .confidence(0.80)
                .suggestedOwnerUserId(OWNER_USER_ID)
                .suggestedOwnerConfidence(0.70)
                .createdBy(OWNER_USER_ID)
                .createdAt(LocalDateTime.of(2026, 8, 15, 14, 0))
                .updatedAt(LocalDateTime.of(2026, 9, 8, 11, 30))
                .owner("Alex Morgan")
                .project("Platform Service")
                .constraints(List.of("out_of_scope"))
                .build());
        // task_008: CANCELLED status (cancelled before completion), unavailable
        seed(InboxTaskRecord.builder()
                .id("task_008")
                .tenantId(TENANT_ID)
                .projectId(PROJECT_CUSTOMER_SUCCESS)
                .title("Schedule Q4 planning meeting")
                .description("Coordinate with team leads for Q4 planning session.")
                .sourceType("manual")
                .tier("operational")
                .roleContext("manager")
                .urgency(1)
                .effortEstimateMinutes(30)
                .deadline(LocalDateTime.of(2026, 9, 30, 17, 0))
                .status("cancelled")
                .isAvailable(false)
                .priorityScore(1.0)
                .extractionProvenance("manual")
                .createdBy(OWNER_USER_ID)
                .createdAt(LocalDateTime.of(2026, 8, 10, 9, 0))
                .updatedAt(LocalDateTime.of(2026, 9, 9, 16, 0))
                .owner("Alex Morgan")
                .project("Customer Success")
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
        // newStatus is one of the 8 real status values (TaskStatusUpdateDTO's
        // @Pattern already validates this before this is called).
        // Store it as-is, converting only to lowercase for consistency with Inbox schema.
        record.setStatus(newStatus.toLowerCase());
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
                // Status is no longer simplified: pass through all 8 real values directly.
                // See docs/employee-workspace-api-contract.md §5.0 for the status model change.
                .status(toUpperCase(record.getStatus()))
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

    /** Utility to convert status to uppercase (no transformation of values). */
    private static String toUpperCase(String status) {
        return status == null ? null : status.toUpperCase();
    }
}
