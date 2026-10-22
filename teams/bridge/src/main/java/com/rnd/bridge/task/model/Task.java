package com.rnd.bridge.task.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.rnd.bridge.core.BridgePayload;

import java.time.Instant;

/**
 * This record is the contract for what Bridge publishes, not a replica of
 * the tasks table. Before adding a field, ask who decides its value - if
 * the answer isn't Bridge, it doesn't belong here.
 * <p>
 * Fields removed on that basis so far: id, version, created_at/updated_at,
 * dependency_task_id, is_available, created_by.
 * <p>
 * {@code @JsonNaming} is on the class rather than relying on a globally
 * configured mapper, so Task carries its own wire contract with Inbox
 * regardless of which ObjectMapper ends up serializing it.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
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
        Integer effortEstimateMinutes,
        // Deal.closeDate is a LocalDate; the spec has this column as
        // TIMESTAMPTZ. The date-to-instant conversion (presumably
        // start-of-day UTC) isn't decided yet - not building it until it is.
        Instant deadline,
        // Set only when status = DONE and the source actually recorded a
        // completion date - e.g. a CRM deal's close date. A DONE task with
        // no completedAt is legitimate (see DealToTaskMapper for the
        // CLOSED_WON-with-no-close-date case), so nothing enforces the two
        // together.
        Instant completedAt) implements BridgePayload {

    @Override
    public String tenantId() {
        return organizationId;
    }

    @Override
    public String dedupKey() {
        return sourceRef;
    }
}
