package com.rnd.chain.repository;

import com.rnd.chain.dto.ConstraintHistoryEntry;

import java.util.List;
import java.util.UUID;

/** Append-only audit trail of constraint creations and status changes. */
public interface ConstraintHistoryRepository {

    /** Appends {@code entry}; joins the caller's transaction so it commits or rolls back with it. */
    void record(ConstraintHistoryEntry entry);

    /** @return the task's entries, newest first; empty list, never null, if there are none. */
    List<ConstraintHistoryEntry> findByTaskId(UUID taskId);
}
