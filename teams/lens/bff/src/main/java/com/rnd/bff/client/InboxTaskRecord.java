package com.rnd.bff.client;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * InboxClient's internal mock representation of a row in the real `tasks`
 * table (shared/database/migrations/V1__init_schema.sql + V4/V6), as
 * opposed to TaskDTO, which is the BFF's frontend-facing contract (consumed
 * by Gateway and lens-frontend, unchanged by this class's existence).
 *
 * Field names and types below intentionally mirror the DB columns 1:1
 * (tenantId <-> tenant_id, priorityScore <-> priority_score, etc.), using
 * the real Postgres enum vocabulary as plain strings (status, sourceType,
 * tier, roleContext, extractionProvenance) rather than TaskDTO's simplified
 * ones. See InboxClient's toTaskDTO()/toFrontendStatus() for how the two
 * relate, including the one place they don't map 1:1: there is no "blocked"
 * value in the real `task_status` enum at all.
 *
 * owner/project stay plain display strings (not resolved from
 * suggestedOwnerUserId/projectId below) — resolving those needs a join to
 * Users/Projects this mock doesn't have. Both the display string and the
 * real id are kept side by side, same as status/priorityScore.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InboxTaskRecord {

    private String id;

    /** Real `tenant_id` (UUID) — single mock tenant, same value on every row. */
    private String tenantId;

    /** Real `project_id` (UUID, nullable). */
    private String projectId;

    private String title;
    private String description;

    /** Real `source_type` enum (github_issue/github_pr/crm_deal/email/slack/whatsapp/meeting/doc/scanned_doc/calendar/manual). */
    private String sourceType;

    /** Real `source_ref` (nullable) — e.g. "PR #4821" for a github_pr source. */
    private String sourceRef;

    /** Real `tier` enum (operational/tactical/strategic), nullable. */
    private String tier;

    /** Real `role_context` enum (developer/manager/higher_manager/field_engineer/salesperson/ceo), nullable. */
    private String roleContext;

    /** Real `urgency` column, 1-5, nullable. */
    private Integer urgency;

    private Integer effortEstimateMinutes;

    /** Real `effort_estimate_confidence`, 0-1, nullable. */
    private Double effortEstimateConfidence;

    private LocalDateTime deadline;

    /** Real `task_status` enum value — see this class's javadoc. */
    private String status;

    /** Real `is_available` column. */
    private Boolean isAvailable;

    /** Real `priority_score` column, 1-10 — see this class's javadoc. */
    private Double priorityScore;

    /** Real `snoozed_until` (required by the DB when status = snoozed), nullable otherwise. */
    private LocalDateTime snoozedUntil;

    /** Real `extraction_provenance` enum (etl/ml_inferred/manual). */
    private String extractionProvenance;

    /** Real `confidence`, 0-1 — required by the DB when extractionProvenance = ml_inferred. */
    private Double confidence;

    /** Real `suggested_owner_user_id` (UUID, nullable) — an ML suggestion, not a confirmed assignment. */
    private String suggestedOwnerUserId;

    /** Real `suggested_owner_confidence`, 0-1, nullable. */
    private Double suggestedOwnerConfidence;

    /** Real `metadata` (JSONB, nullable) — no defined shape yet. */
    private Map<String, Object> metadata;

    /** Real `created_by` (UUID, nullable). */
    private String createdBy;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;

    private String owner;
    private String project;

    private List<String> dependencies;
    private List<String> constraints;
}
