package com.rnd.chain.repository;

import com.rnd.chain.dto.DependencyChangeSource;
import com.rnd.chain.dto.DependencyHistoryEntry;
import com.rnd.chain.dto.PageResponse;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory {@link DependencyHistoryRepository}. record() dedups atomically
 * under a lock; findByTaskId() is O(n) over all entries, not indexed.
 */
public class InMemoryDependencyHistoryRepository implements DependencyHistoryRepository {

    private final ConcurrentHashMap<UUID, DependencyHistoryEntry> entriesById = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UUID> idBySourceRefKey = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();

    @Override
    public DependencyHistoryEntry record(DependencyHistoryEntry entry) {
        Objects.requireNonNull(entry, "entry must not be null");
        String sourceRef = entry.getSourceRef();
        if (sourceRef == null) {
            entriesById.put(entry.getId(), entry);
            return entry;
        }
        String key = dedupKey(entry.getSource(), sourceRef);
        synchronized (writeLock) {
            UUID existingId = idBySourceRefKey.get(key);
            if (existingId != null) {
                return entriesById.get(existingId);
            }
            entriesById.put(entry.getId(), entry);
            idBySourceRefKey.put(key, entry.getId());
            return entry;
        }
    }

    @Override
    public boolean existsBySourceAndSourceRef(DependencyChangeSource source, String sourceRef) {
        if (sourceRef == null) {
            return false;
        }
        return idBySourceRefKey.containsKey(dedupKey(source, sourceRef));
    }

    @Override
    public PageResponse<DependencyHistoryEntry> findByTaskId(UUID taskId, int page, int size) {
        List<DependencyHistoryEntry> matching = entriesById.values().stream()
                .filter(entry -> entry.getTaskId().equals(taskId))
                .sorted(Comparator.comparing(DependencyHistoryEntry::getCreatedAt)
                        .thenComparing(DependencyHistoryEntry::getId))
                .collect(Collectors.toList())
                .reversed();

        long totalElements = matching.size();
        int totalPages = (int) Math.ceil(totalElements / (double) size);

        int fromIndex = Math.min(page * size, matching.size());
        int toIndex = Math.min(fromIndex + size, matching.size());
        List<DependencyHistoryEntry> content = matching.subList(fromIndex, toIndex);

        return new PageResponse<>(content, page, size, totalElements, totalPages);
    }

    private static String dedupKey(DependencyChangeSource source, String sourceRef) {
        return source.name() + "|" + sourceRef;
    }
}
