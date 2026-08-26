package com.rnd.bridge.task.model;

import com.rnd.bridge.core.BridgePayload;

import java.time.Instant;

/**
 * This record is the contract for what Bridge publishes, not a replica of
 * the tasks table. Before adding a field, ask who decides its value - if
 * the answer isn't Bridge, it doesn't belong here.
 * <p>
 * Fields removed on that basis so far: id, version, created_at/updated_at,
 * dependency_task_id, is_available, completed_at, created_by.
 */
public record Task(
        String organizationId,
        String projectId,
        String title,
        String description,
        TaskStatus status,
        Integer urgency,
        Double confidence,
        Tier tier,
        ExtractionProvenance extractionProvenance,
        RoleContext roleContext,
        SourceType sourceType,
        String sourceRef,
        Integer estimatedDurationMinutes,
        // Deal.closeDate is a LocalDate; the spec has this column as
        // TIMESTAMPTZ. The date-to-instant conversion (presumably
        // start-of-day UTC) isn't decided yet - not building it until it is.
        Instant deadline,
        Instant snoozedUntil) implements BridgePayload {

    @Override
    public String tenantId() {
        return organizationId;
    }

    @Override
    public String dedupKey() {
        return sourceRef;
    }
}
