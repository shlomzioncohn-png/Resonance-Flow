package com.rnd.chain.repository;

import com.rnd.chain.dto.DependencyChangeSource;
import com.rnd.chain.dto.DependencyHistoryEntry;
import com.rnd.chain.dto.PageResponse;

import java.util.Optional;
import java.util.UUID;

/** Persistence port for dependency-change history entries - no REST/Kafka/DB coupling. */
public interface DependencyHistoryRepository {

    /**
     * Records {@code entry}. If sourceRef is non-null and (source, sourceRef)
     * already exists, this is a no-op - returns the existing entry, not a new one.
     */
    DependencyHistoryEntry record(DependencyHistoryEntry entry);

    /**
     * The entry already recorded under this exact (source, sourceRef), if any - lets a
     * caller verify a retry actually matches what was recorded, not just that some
     * entry with this key exists (see {@code TaskDependencyService}'s idempotency check).
     */
    Optional<DependencyHistoryEntry> findBySourceAndSourceRef(DependencyChangeSource source, String sourceRef);

    /** Newest first: createdAt descending, true insertion order as tiebreaker (not id - see implementation). */
    PageResponse<DependencyHistoryEntry> findByTaskId(UUID taskId, int page, int size);
}
