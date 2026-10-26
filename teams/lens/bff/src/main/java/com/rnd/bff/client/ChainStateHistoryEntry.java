package com.rnd.bff.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * One row of Chain's GET /tasks/{taskId}/state-history. eventType is
 * STATUS_CHANGED (fromStatus/toStatus set) or BLOCKED / UNBLOCKED
 * (blockingCountBefore/After set); Chain leaves out the fields that don't apply.
 * Statuses are Chain's: PENDING, ACTIVE, DONE.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChainStateHistoryEntry(String id, String taskId, String eventType,
                                     String fromStatus, String toStatus,
                                     Integer blockingCountBefore, Integer blockingCountAfter,
                                     Instant createdAt) {
}
