package com.rnd.bff.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * A task/work item, aggregated from Inbox (task data), Chain (dependencies),
 * and Mind (scoring) once those integrations exist. Mirrors
 * com.rnd.gateway.dto.TaskDTO field-for-field — each layer owns its own copy
 * of the DTO rather than sharing a library module. See
 * docs/workspace-screen-integration-plan.md.
 *
 * Two groups of fields below on purpose: the original frontend-facing ones
 * (status/priority/effortEstimate/dueDate/owner/project) are untouched, so
 * Gateway and lens-frontend keep working exactly as before. Everything from
 * `inboxStatus` down is additive — the real `tasks` table columns
 * (shared/database/migrations), surfaced as-is so the schema the code
 * actually works against is visible (e.g. in Swagger), without breaking the
 * existing contract. See com.rnd.bff.client.InboxClient#toTaskDTO for where
 * these are populated and InboxTaskRecord for the internal source of truth.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TaskDTO {

    private String id;
    private String title;
    private String description;

    /** READY / IN_PROGRESS / BLOCKED / COMPLETED */
    private String status;

    /** LOW / MEDIUM / HIGH */
    private String priority;

    /** Estimated effort, in minutes. */
    private Integer effortEstimate;

    private LocalDateTime dueDate;
    private String owner;
    private String project;

    /** Task IDs this task depends on. Empty list = no dependencies. */
    private List<String> dependencies;

    /** Blockers, e.g. "approval_needed". Empty list = no constraints. */
    private List<String> constraints;

    // -------------------------------------------------------------------------
    // Below: real `tasks` table columns, added alongside the fields above
    // rather than replacing them — see this class's javadoc.
    // -------------------------------------------------------------------------

    /** Real `tenant_id` (UUID). */
    private String tenantId;

    /** Real `project_id` (UUID, nullable) — `project` above stays the display name. */
    private String projectId;

    /** Real `source_type` enum (github_issue/github_pr/crm_deal/email/slack/whatsapp/meeting/doc/scanned_doc/calendar/manual). */
    private String sourceType;

    /** Real `source_ref`, nullable — e.g. "PR #4821" for a github_pr source. */
    private String sourceRef;

    /** Real `tier` enum (operational/tactical/strategic), nullable. */
    private String tier;

    /** Real `role_context` enum (developer/manager/higher_manager/field_engineer/salesperson/ceo), nullable. */
    private String roleContext;

    /** Real `urgency` column, 1-5, nullable. */
    private Integer urgency;

    /** Real `effort_estimate_confidence`, 0-1, nullable. */
    private Double effortEstimateConfidence;

    /**
     * The real `tasks.status` value (new/in_review/approved/in_progress/
     * snoozed/done/rejected/cancelled) — see `status` above for the
     * simplified equivalent and com.rnd.bff.client.InboxClient#toFrontendStatus
     * for how the two relate.
     */
    private String inboxStatus;

    /** Real `is_available` column. */
    private Boolean isAvailable;

    /** Real `priority_score` column (1-10) — `priority` above is derived from this. */
    private Double priorityScore;

    /** Real `snoozed_until`, set when inboxStatus = snoozed. */
    private LocalDateTime snoozedUntil;

    /** Real `extraction_provenance` enum (etl/ml_inferred/manual). */
    private String extractionProvenance;

    /** Real `confidence`, 0-1, nullable. */
    private Double confidence;

    /** Real `suggested_owner_user_id` (UUID, nullable) — an ML suggestion, not a confirmed assignment. */
    private String suggestedOwnerUserId;

    /** Real `suggested_owner_confidence`, 0-1, nullable. */
    private Double suggestedOwnerConfidence;

    /** Real `metadata` (JSONB, nullable). */
    private Map<String, Object> metadata;

    /** Real `created_by` (UUID, nullable). */
    private String createdBy;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;
}
