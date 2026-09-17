package com.rnd.chain.repository;

import com.rnd.chain.dto.DependencyChangeSource;
import com.rnd.chain.dto.DependencyHistoryEntry;
import com.rnd.chain.dto.PageResponse;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * In-memory {@link DependencyHistoryRepository}. record() dedups atomically
 * under a lock; findByTaskId() is O(n) over all entries, not indexed.
 *
 * <p>Ordering does not rely on {@code createdAt} alone: two entries recorded in
 * the same millisecond would otherwise tie, and {@code Clock} resolution can't
 * be trusted to always tell them apart. Each entry also gets a monotonically
 * increasing sequence number at insertion time, used only as the tiebreaker.
 */
public class InMemoryDependencyHistoryRepository implements DependencyHistoryRepository {

    private final ConcurrentHashMap<UUID, DependencyHistoryEntry> entriesById = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UUID> idBySourceRefKey = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> sequenceById = new ConcurrentHashMap<>();
    private final AtomicLong sequenceCounter = new AtomicLong();
    private final Object writeLock = new Object();

    @Override
    public DependencyHistoryEntry record(DependencyHistoryEntry entry) {
        Objects.requireNonNull(entry, "entry must not be null");
        String sourceRef = entry.sourceRef();
        synchronized (writeLock) {
            if (sourceRef != null) {
                String key = dedupKey(entry.source(), sourceRef);
                UUID existingId = idBySourceRefKey.get(key);
                if (existingId != null) {
                    return entriesById.get(existingId);
                }
                idBySourceRefKey.put(key, entry.id());
            }
            sequenceById.put(entry.id(), sequenceCounter.incrementAndGet());
            entriesById.put(entry.id(), entry);
            return entry;
        }
    }

    @Override
    public Optional<DependencyHistoryEntry> findBySourceAndSourceRef(DependencyChangeSource source, String sourceRef) {
        if (sourceRef == null) {
            return Optional.empty();
        }
        UUID existingId = idBySourceRefKey.get(dedupKey(source, sourceRef));
        return existingId == null ? Optional.empty() : Optional.ofNullable(entriesById.get(existingId));
    }

    @Override
    public PageResponse<DependencyHistoryEntry> findByTaskId(UUID taskId, int page, int size) {
        List<DependencyHistoryEntry> matching = entriesById.values().stream()
                .filter(entry -> entry.taskId().equals(taskId))
                .sorted(Comparator.comparing(DependencyHistoryEntry::createdAt)
                        .thenComparing(entry -> sequenceById.get(entry.id())))
                .collect(Collectors.toList())
                .reversed();

        long totalElements = matching.size();
        int totalPages = (int) Math.ceil(totalElements / (double) size);

        // page/size come from an untrusted caller (the controller only caps size, not page) - do the
        // multiplication in long so a huge page number clamps to matching.size() instead of overflowing.
        long requestedFromIndex = (long) page * size;
        int fromIndex = (int) Math.min(requestedFromIndex, matching.size());
        int toIndex = Math.min(fromIndex + size, matching.size());
        List<DependencyHistoryEntry> content = matching.subList(fromIndex, toIndex);

        return new PageResponse<>(content, page, size, totalElements, totalPages);
    }

    @Override
    public void delete(UUID entryId) {
        Objects.requireNonNull(entryId, "entryId must not be null");
        synchronized (writeLock) {
            DependencyHistoryEntry removed = entriesById.remove(entryId);
            sequenceById.remove(entryId);
            if (removed != null && removed.sourceRef() != null) {
                idBySourceRefKey.remove(dedupKey(removed.source(), removed.sourceRef()), entryId);
            }
        }
    }

    private static String dedupKey(DependencyChangeSource source, String sourceRef) {
        return source.name() + "|" + sourceRef;
    }
}
