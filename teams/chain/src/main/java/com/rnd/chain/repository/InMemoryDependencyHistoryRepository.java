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
 * be trusted to always tell them apart. Each entry is stored together with a
 * monotonically increasing sequence number, used only as the tiebreaker - the two
 * are kept in a single map entry (not two separate maps) so findByTaskId() can read
 * an entry's sequence without ever observing one without the other, and therefore
 * needs no lock of its own.
 */
public class InMemoryDependencyHistoryRepository implements DependencyHistoryRepository {

    private record Sequenced(DependencyHistoryEntry entry, long sequence) {
    }

    private final ConcurrentHashMap<UUID, Sequenced> entriesById = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UUID> idBySourceRefKey = new ConcurrentHashMap<>();
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
                    return entriesById.get(existingId).entry();
                }
                idBySourceRefKey.put(key, entry.id());
            }
            entriesById.put(entry.id(), new Sequenced(entry, sequenceCounter.incrementAndGet()));
            return entry;
        }
    }

    @Override
    public Optional<DependencyHistoryEntry> findBySourceAndSourceRef(DependencyChangeSource source, String sourceRef) {
        if (sourceRef == null) {
            return Optional.empty();
        }
        UUID existingId = idBySourceRefKey.get(dedupKey(source, sourceRef));
        if (existingId == null) {
            return Optional.empty();
        }
        Sequenced found = entriesById.get(existingId);
        return found == null ? Optional.empty() : Optional.of(found.entry());
    }

    @Override
    public PageResponse<DependencyHistoryEntry> findByTaskId(UUID taskId, int page, int size) {
        // No lock: entriesById's values are immutable Sequenced records, each inserted as a
        // single atomic put in record()/removed as a single atomic remove() in delete() - so a
        // concurrent write can never be observed here half-applied.
        List<DependencyHistoryEntry> matching = entriesById.values().stream()
                .filter(s -> s.entry().taskId().equals(taskId))
                .sorted(Comparator.comparing((Sequenced s) -> s.entry().createdAt())
                        .thenComparing(Sequenced::sequence))
                .map(Sequenced::entry)
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
            Sequenced removed = entriesById.remove(entryId);
            if (removed != null && removed.entry().sourceRef() != null) {
                idBySourceRefKey.remove(dedupKey(removed.entry().source(), removed.entry().sourceRef()), entryId);
            }
        }
    }

    private static String dedupKey(DependencyChangeSource source, String sourceRef) {
        return source.name() + "|" + sourceRef;
    }
}
