package com.rnd.chain.repository;

import com.rnd.chain.dto.PageResponse;
import com.rnd.chain.dto.TaskStateHistoryEntry;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory {@link TaskStateHistoryRepository}; findByTaskId() is O(n) over all entries.
 * Each entry is stored with a monotonically increasing sequence, the same-millisecond
 * tiebreaker (mirrors the Postgres {@code sequence} column), kept in one immutable record so
 * a reader never sees an entry without its sequence.
 */
public class InMemoryTaskStateHistoryRepository implements TaskStateHistoryRepository {

    private record Sequenced(TaskStateHistoryEntry entry, long sequence) {
    }

    private final List<Sequenced> entries = new CopyOnWriteArrayList<>();
    private final AtomicLong sequenceCounter = new AtomicLong();

    @Override
    public TaskStateHistoryEntry record(TaskStateHistoryEntry entry) {
        Objects.requireNonNull(entry, "entry must not be null");
        entries.add(new Sequenced(entry, sequenceCounter.incrementAndGet()));
        return entry;
    }

    @Override
    public PageResponse<TaskStateHistoryEntry> findByTaskId(UUID taskId, Instant from, Instant to, int page,
                                                            int size) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        List<TaskStateHistoryEntry> matching = entries.stream()
                .filter(s -> s.entry().taskId().equals(taskId))
                .filter(s -> from == null || !s.entry().createdAt().isBefore(from))
                .filter(s -> to == null || s.entry().createdAt().isBefore(to))
                .sorted(Comparator.comparing((Sequenced s) -> s.entry().createdAt())
                        .thenComparing(Sequenced::sequence).reversed())
                .map(Sequenced::entry)
                .toList();

        long totalElements = matching.size();
        int totalPages = (int) Math.ceil(totalElements / (double) size);

        // page comes from an untrusted caller - multiply in long so a huge page clamps instead of overflowing.
        int fromIndex = (int) Math.min((long) page * size, matching.size());
        int toIndex = Math.min(fromIndex + size, matching.size());
        return new PageResponse<>(matching.subList(fromIndex, toIndex), page, size, totalElements, totalPages);
    }
}
