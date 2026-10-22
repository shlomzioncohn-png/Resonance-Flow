package com.rnd.chain.repository;

import com.rnd.chain.dto.PageResponse;
import com.rnd.chain.dto.TaskStateHistoryEntry;

import java.time.Instant;
import java.util.UUID;
import java.util.List;
import java.util.Map;

/**
 * Persistence port for the append-only task state history - no REST/Kafka/DB coupling.
 * Call {@code record} only from the service that makes the change it describes, inside the
 * same transaction - calling it directly records a change that may never have happened.
 */
public interface TaskStateHistoryRepository {

    /** Appends {@code entry}. No dedup - deciding whether an event is stale/duplicate is the caller's job. */
    TaskStateHistoryEntry record(TaskStateHistoryEntry entry);

    /**
     * Entries for {@code taskId} with {@code from <= createdAt < to}; a null bound is open.
     * Newest first: createdAt descending, true insertion order as tiebreaker.
     */
    PageResponse<TaskStateHistoryEntry> findByTaskId(UUID taskId, Instant from, Instant to, int page, int size);

    Map<UUID, Instant> findLatestBlockedSinceByTaskIds(List<UUID> taskIds);
}
