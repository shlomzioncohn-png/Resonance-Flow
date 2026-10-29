package com.rnd.bridge.task;

import com.google.protobuf.Timestamp;
import com.rnd.bridge.core.error.ValidationException;
import com.rnd.bridge.task.model.RoleContext;
import com.rnd.bridge.task.model.SourceType;
import com.rnd.bridge.task.model.Task;
import com.rnd.bridge.task.model.TaskStatus;
import com.rnd.bridge.task.model.Tier;
import com.rnd.inbox.event.StructuredTaskEvent;
import com.rnd.inbox.event.TaskRoleContext;
import com.rnd.inbox.event.TaskTier;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Builds Inbox's real {@code bridge.structured.tasks} wire message
 * ({@code com.rnd.inbox.event.StructuredTaskEvent}, compiled from the registered schema in
 * {@code shared/proto/structured-task-event.proto}) directly from a {@link Task}.
 * <p>
 * The only wire shape Bridge produces now - an earlier JSON DTO ({@code BridgeTaskEventDto}) was
 * removed as unused (PR #1097). Pure mapper: no Kafka, no Spring wiring, no I/O.
 * <p>
 * {@code com.rnd.bridge.task.model.SourceType}/{@code TaskStatus} and
 * {@code com.rnd.inbox.event.SourceType}/{@code TaskStatus} share simple names - two teams
 * independently named things the same way. Java has no import-aliasing, so the proto types are
 * referenced fully-qualified below wherever both are in scope, rather than imported under a
 * colliding name.
 * <p>
 * Every enum mapping below is an explicit, exhaustive {@code switch} over the Bridge-side enum -
 * never a {@code name()}/prefix guess. Adding a value to a Bridge enum without updating the
 * matching {@code switch} here fails the build (a non-exhaustive switch expression over an enum
 * is a compile error), not a silent {@code *_UNSPECIFIED}.
 */
public final class StructuredTaskEventMapper {

    private StructuredTaskEventMapper() {
    }

    public static StructuredTaskEvent from(Task task) {
        String tenantId = task.organizationId(); // Task.organizationId() -> tenant_id, deliberate rename (CLAUDE.md §4/§6)
        requireValidTenantId(tenantId);
        requireNonBlank(task.title(), "title");
        requireNonBlank(task.sourceRef(), "sourceRef");
        if (task.sourceType() == null) {
            throw new ValidationException(
                    "Invalid Task.sourceType = null: must not be null - Inbox would dead-letter a message with no source_type");
        }

        StructuredTaskEvent.Builder builder = StructuredTaskEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setTenantId(tenantId)
                .setTitle(task.title())
                .setSourceType(toProtoSourceType(task.sourceType()))
                .setSourceRef(task.sourceRef());

        if (task.projectId() != null) {
            builder.setProjectId(task.projectId());
        }
        if (task.description() != null) {
            builder.setDescription(task.description());
        }
        if (task.tier() != null) {
            builder.setTier(toProtoTier(task.tier()));
        }
        if (task.roleContext() != null) {
            builder.setRoleContext(toProtoRoleContext(task.roleContext()));
        }
        if (task.urgency() != null) {
            builder.setUrgency(task.urgency());
        }
        if (task.effortEstimateMinutes() != null) {
            builder.setEffortEstimateMinutes(task.effortEstimateMinutes());
        }
        if (task.deadline() != null) {
            builder.setDeadline(toTimestamp(task.deadline()));
        }
        if (task.status() != null) {
            builder.setStatus(toProtoStatus(task.status()));
        }
        if (task.confidence() != null) {
            builder.setConfidence(task.confidence());
        }
        // created_by_user_id: no source on Task today - left unset, deliberately.
        // assignees: no source on Task today - left empty, deliberately.

        return builder.build();
    }

    // Mirrors com.rnd.inbox.shared.messaging.validation.ValidUuidValidator's regex exactly
    // (canonical 36-char 8-4-4-4-12 hex-hyphenated shape, case-insensitive) - not
    // UUID.fromString(), which is lenient enough to accept malformed strings like "1-1-1-1-1"
    // that Inbox's own validator rejects. Using the laxer check here would let Bridge publish a
    // tenant_id Inbox dead-letters anyway.
    private static final Pattern INBOX_UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private static void requireValidTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new ValidationException(
                    "Invalid Task.organizationId = " + tenantId + ": must not be blank (maps to tenant_id)");
        }
        if (!INBOX_UUID_PATTERN.matcher(tenantId).matches()) {
            throw new ValidationException(
                    "Invalid Task.organizationId = " + tenantId
                            + ": must be a canonical UUID (8-4-4-4-12 hex) - Inbox's own @ValidUuid would reject this (maps to tenant_id)");
        }
    }

    private static void requireNonBlank(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ValidationException("Invalid Task." + field + " = " + value + ": must not be blank");
        }
    }

    private static com.rnd.inbox.event.SourceType toProtoSourceType(SourceType sourceType) {
        return switch (sourceType) {
            case GITHUB_ISSUE -> com.rnd.inbox.event.SourceType.SOURCE_TYPE_GITHUB_ISSUE;
            case GITHUB_PR -> com.rnd.inbox.event.SourceType.SOURCE_TYPE_GITHUB_PR;
            case JIRA_ISSUE -> com.rnd.inbox.event.SourceType.SOURCE_TYPE_JIRA_ISSUE;
            case CRM_DEAL -> com.rnd.inbox.event.SourceType.SOURCE_TYPE_CRM_DEAL;
            case EMAIL -> com.rnd.inbox.event.SourceType.SOURCE_TYPE_EMAIL;
            case SLACK -> com.rnd.inbox.event.SourceType.SOURCE_TYPE_SLACK;
            case WHATSAPP -> com.rnd.inbox.event.SourceType.SOURCE_TYPE_WHATSAPP;
            case MEETING -> com.rnd.inbox.event.SourceType.SOURCE_TYPE_MEETING;
            case DOC -> com.rnd.inbox.event.SourceType.SOURCE_TYPE_DOC;
            case SCANNED_DOC -> com.rnd.inbox.event.SourceType.SOURCE_TYPE_SCANNED_DOC;
            case CALENDAR -> com.rnd.inbox.event.SourceType.SOURCE_TYPE_CALENDAR;
        };
    }

    private static TaskTier toProtoTier(Tier tier) {
        return switch (tier) {
            case OPERATIONAL -> TaskTier.TASK_TIER_OPERATIONAL;
            case TACTICAL -> TaskTier.TASK_TIER_TACTICAL;
            case STRATEGIC -> TaskTier.TASK_TIER_STRATEGIC;
        };
    }

    private static TaskRoleContext toProtoRoleContext(RoleContext roleContext) {
        return switch (roleContext) {
            case DEVELOPER -> TaskRoleContext.TASK_ROLE_CONTEXT_DEVELOPER;
            case MANAGER -> TaskRoleContext.TASK_ROLE_CONTEXT_MANAGER;
            case HIGHER_MANAGER -> TaskRoleContext.TASK_ROLE_CONTEXT_HIGHER_MANAGER;
            case FIELD_ENGINEER -> TaskRoleContext.TASK_ROLE_CONTEXT_FIELD_ENGINEER;
            case SALESPERSON -> TaskRoleContext.TASK_ROLE_CONTEXT_SALESPERSON;
            case CEO -> TaskRoleContext.TASK_ROLE_CONTEXT_CEO;
        };
    }

    private static com.rnd.inbox.event.TaskStatus toProtoStatus(TaskStatus status) {
        return switch (status) {
            case NEW -> com.rnd.inbox.event.TaskStatus.TASK_STATUS_NEW;
            case IN_REVIEW -> com.rnd.inbox.event.TaskStatus.TASK_STATUS_IN_REVIEW;
            case APPROVED -> com.rnd.inbox.event.TaskStatus.TASK_STATUS_APPROVED;
            case IN_PROGRESS -> com.rnd.inbox.event.TaskStatus.TASK_STATUS_IN_PROGRESS;
            case DONE -> com.rnd.inbox.event.TaskStatus.TASK_STATUS_DONE;
            case REJECTED -> com.rnd.inbox.event.TaskStatus.TASK_STATUS_REJECTED;
            case CANCELLED -> com.rnd.inbox.event.TaskStatus.TASK_STATUS_CANCELLED;
        };
    }

    private static Timestamp toTimestamp(Instant instant) {
        return Timestamp.newBuilder()
                .setSeconds(instant.getEpochSecond())
                .setNanos(instant.getNano())
                .build();
    }
}
