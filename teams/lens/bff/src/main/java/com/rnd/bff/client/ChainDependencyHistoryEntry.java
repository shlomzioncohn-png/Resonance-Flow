package com.rnd.bff.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

/**
 * One row of Chain's GET /tasks/{taskId}/dependencies/history: taskId started
 * (ADDED) or stopped (REMOVED) depending on dependsOnTaskId. source is MIND,
 * USER or SYSTEM. Chain doesn't record who did it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChainDependencyHistoryEntry(String id, String taskId, String dependsOnTaskId,
                                          String action, String source, String sourceRef,
                                          Instant createdAt) {
}
