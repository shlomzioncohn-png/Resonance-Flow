package com.rnd.bridge.task;

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
 * the globally configured naming strategy
 * ({@code spring.jackson.property-naming-strategy: SNAKE_CASE}), not from
 * an annotation on this record.
 * <p>
 * Fields the contract says Inbox generates itself and forbids sending - some
 * of which exist on {@code Task} - are deliberately absent: {@code id},
 * {@code correlationId}, {@code isAvailable}, {@code priorityScore}, {@code
 * ownerId}, {@code createdBy}, {@code extractionProvenance}, {@code
 * createdAt}, {@code updatedAt}, {@code completedAt}, {@code snoozedUntil}.
 * <p>
 * {@code eventId} is audit correlation only, not the idempotency key
 * (that's {@code organizationId + sourceType + sourceRef}), so a fresh UUID
 * per publish attempt - not derived from the {@link Task} - is correct.
 */
public record BridgeTaskEventDto(
        String eventId,
        String organizationId,
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
                task.organizationId(),
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
