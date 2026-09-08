package com.rnd.chain.repository;

import com.rnd.chain.dto.DependencyChangeSource;
import com.rnd.chain.dto.DependencyHistoryEntry;
import com.rnd.chain.dto.PageResponse;

import java.util.UUID;

/**
 * Persistence port for dependency-change history entries. Pure persistence
 * contract - no dependency on REST/Kafka/DB-specific types, and no
 * enforcement of when a caller is required to supply a source ref; that is
 * a business rule owned by {@code TaskDependencyService}.
 */
public interface DependencyHistoryRepository {

    /**
     * Records the given entry atomically. If entry.getSourceRef() is
     * non-null and an entry with the same (source, sourceRef) already
     * exists, this is a no-op - returns the EXISTING entry, not a new one.
     * If sourceRef is null, always inserts a new, distinct row (no dedup
     * possible without a key).
     */
    DependencyHistoryEntry record(DependencyHistoryEntry entry);

    /** true if an entry with this exact (source, sourceRef) already exists. */
    boolean existsBySourceAndSourceRef(DependencyChangeSource source, String sourceRef);

    /**
     * Chronologically deterministic order, newest first: createdAt
     * descending, id descending as tiebreaker (stable even for
     * same-millisecond writes). History is read newest-first, like a
     * git log or audit trail.
     */
    PageResponse<DependencyHistoryEntry> findByTaskId(UUID taskId, int page, int size);
}
