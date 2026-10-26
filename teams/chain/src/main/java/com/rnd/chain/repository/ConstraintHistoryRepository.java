package com.rnd.chain.repository;

import com.rnd.chain.dto.ConstraintHistoryEntry;
import com.rnd.chain.dto.PageResponse;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Append-only audit trail of constraint creations and status changes. */
public interface ConstraintHistoryRepository {

    /** Appends {@code entry}; joins the caller's transaction so it commits or rolls back with it. */
    void record(ConstraintHistoryEntry entry);

    /** @return the task's entries, newest first; empty list, never null, if there are none. */
    List<ConstraintHistoryEntry> findByTaskId(UUID taskId);

    /**
     * @return one page of the task's entries with {@code from <= createdAt < to}, newest first;
     * a null bound is open.
     */
    PageResponse<ConstraintHistoryEntry> findByTaskId(UUID taskId, Instant from, Instant to, int page, int size);

    /** @return whether the task has at least one history entry. */
    boolean existsByTaskId(UUID taskId);
}
