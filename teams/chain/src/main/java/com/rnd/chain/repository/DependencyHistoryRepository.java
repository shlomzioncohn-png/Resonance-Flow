package com.rnd.chain.repository;

import com.rnd.chain.dto.DependencyChangeSource;
import com.rnd.chain.dto.DependencyHistoryEntry;
import com.rnd.chain.dto.PageResponse;

import java.util.UUID;

/** Persistence port for dependency-change history entries - no REST/Kafka/DB coupling. */
public interface DependencyHistoryRepository {

    /**
     * Records {@code entry}. If sourceRef is non-null and (source, sourceRef)
     * already exists, this is a no-op - returns the existing entry, not a new one.
     */
    DependencyHistoryEntry record(DependencyHistoryEntry entry);

    /** true if an entry with this exact (source, sourceRef) already exists. */
    boolean existsBySourceAndSourceRef(DependencyChangeSource source, String sourceRef);

    /** Newest first: createdAt descending, id descending as tiebreaker. */
    PageResponse<DependencyHistoryEntry> findByTaskId(UUID taskId, int page, int size);
}
