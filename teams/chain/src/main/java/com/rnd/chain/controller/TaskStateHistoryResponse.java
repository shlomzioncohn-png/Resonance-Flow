package com.rnd.chain.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.rnd.chain.dto.TaskStateEventType;
import com.rnd.chain.dto.TaskStateHistoryEntry;
import com.rnd.chain.dto.TaskStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * API-layer DTO for {@link TaskStateHistoryEntry}, kept separate so JSON-shape changes never touch the domain model.
 * Fields that do not apply to the {@code eventType} are omitted from the JSON rather than sent as null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TaskStateHistoryResponse(UUID id, UUID taskId, TaskStateEventType eventType,
                                        TaskStatus fromStatus, TaskStatus toStatus,
                                        Integer blockingCountBefore, Integer blockingCountAfter,
                                        Instant createdAt) {

    public static TaskStateHistoryResponse from(TaskStateHistoryEntry entry) {
        return new TaskStateHistoryResponse(entry.id(), entry.taskId(), entry.eventType(),
                entry.fromStatus(), entry.toStatus(), entry.blockingCountBefore(),
                entry.blockingCountAfter(), entry.createdAt());
    }
}
