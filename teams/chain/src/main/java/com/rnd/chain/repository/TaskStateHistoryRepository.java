package com.rnd.chain.repository;

import com.rnd.chain.dto.PageResponse;
import com.rnd.chain.dto.TaskStateEventType;
import com.rnd.chain.dto.TaskStateHistoryEntry;
import com.rnd.chain.dto.TaskStateHistoryRow;

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

    /** Latest BLOCKED time per task, even if the task was unblocked since; callers pass only blocked tasks. */
    Map<UUID, Instant> findLatestBlockedSinceByTaskIds(List<UUID> taskIds);

    /**
     * The tenant's events with {@code from <= createdAt < to} of the given types, strictly after
     * {@code (afterTime, afterSequence)}, oldest first: createdAt ascending, sequence as tiebreaker.
     */
    List<TaskStateHistoryRow> findByTenant(UUID tenantId, Instant from, Instant to, List<TaskStateEventType> types,
                                           Instant afterTime, long afterSequence, int limit);

    /** Tasks whose latest BLOCKED/UNBLOCKED event is BLOCKED, with that event's time. Null or empty {@code taskIds} means all. */
    Map<UUID, Instant> findCurrentlyBlockedSince(UUID tenantId, List<UUID> taskIds);
}
