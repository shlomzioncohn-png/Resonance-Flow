package com.rnd.bridge.task;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.rnd.bridge.task.model.Task;

import java.time.Instant;
import java.util.UUID;

/**
 * The wire shape Inbox's Kafka ingestion contract
 * (bridge.structured.tasks, esti/inbox-kafka-ingestion-contract) actually
 * expects: snake_case keys and UPPER_SNAKE_CASE enum values.
 * <p>
 * {@link Task} is deliberately snake_case with lowercase enums - it is built
 * against Inbox's *database* schema, and {@code TaskSerializationTest} pins
 * that. This is the same information addressed to a different reader, so it
 * cannot reuse Task's {@code @JsonValue} serialization; enum fields here
 * carry the raw {@code Enum.name()} instead. The snake_case keys come from
 * the {@code @JsonNaming} annotation on this record, not from a global
 * configuration, so the shared ObjectMapper stays on Jackson's default
 * camelCase strategy for HubSpot's API responses.
 * <p>
 * Fields the contract says Inbox generates itself and forbids sending - some
 * of which exist on {@code Task} - are deliberately absent: {@code id},
 * {@code correlationId}, {@code isAvailable}, {@code priorityScore}, {@code
 * ownerId}, {@code createdBy}, {@code extractionProvenance}, {@code
 * createdAt}, {@code updatedAt}, {@code completedAt}.
 * <p>
 * {@code eventId} is audit correlation only, not the idempotency key
 * (that's {@code tenantId + sourceType + sourceRef}), so a fresh UUID
 * per publish attempt - not derived from the {@link Task} - is correct.
 * <p>
 * {@code tenantId} - not {@code organizationId}, unlike {@link Task} - matches
 * Inbox's actual {@code tasks.tenant_id} column and
 * {@code StructuredTaskEventDto.tenantId}. Inbox renamed
 * {@code organization_id} to {@code tenant_id} across their schema on
 * 2026-09-06 (#255, {@code 9684b8e8}), including the idempotency index; this
 * field was still called {@code organizationId} for over a week afterward,
 * so every message Bridge published in that window was silently rejected by
 * Inbox's {@code @NotBlank} validation on {@code tenantId} (see
 * {@code CLAUDE.md} §6). {@link Task#organizationId()} is deliberately NOT
 * renamed to match - see {@code CLAUDE.md} §4/§6 for why.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record BridgeTaskEventDto(
        String eventId,
        String tenantId,
        String projectId,
        String title,
        String description,
        String sourceType,
        String sourceRef,
        String tier,
        String roleContext,
        Integer urgency,
        Integer effortEstimateMinutes,
        Instant deadline,
        String status,
        Double confidence) {

    public static BridgeTaskEventDto from(Task task) {
        return new BridgeTaskEventDto(
                UUID.randomUUID().toString(),
                task.organizationId(), // Task.organizationId() -> this record's tenantId, deliberately - see class Javadoc
                task.projectId(),
                task.title(),
                task.description(),
                task.sourceType() == null ? null : task.sourceType().name(),
                task.sourceRef(),
                task.tier() == null ? null : task.tier().name(),
                task.roleContext() == null ? null : task.roleContext().name(),
                task.urgency(),
                task.effortEstimateMinutes(),
                task.deadline(),
                task.status() == null ? null : task.status().name(),
                task.confidence());
    }
}
