package com.rnd.chain.repository;

import com.rnd.chain.dto.PageResponse;
import com.rnd.chain.dto.TaskStateHistoryEntry;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.repository.entity.TaskStateHistoryEntity;
import com.rnd.chain.repository.jpa.SpringDataTaskStateHistoryJpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Postgres-backed {@link TaskStateHistoryRepository} (V8 migration). */
@Repository
public class PostgresTaskStateHistoryRepository implements TaskStateHistoryRepository {

    // Stand-ins for a null (open) bound; well inside timestamptz's range.
    private static final Instant OPEN_FROM = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant OPEN_TO = Instant.parse("9999-12-31T23:59:59Z");

    private final SpringDataTaskStateHistoryJpaRepository jpaRepository;

    public PostgresTaskStateHistoryRepository(SpringDataTaskStateHistoryJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional
    public TaskStateHistoryEntry record(TaskStateHistoryEntry entry) {
        Objects.requireNonNull(entry, "entry must not be null");
        jpaRepository.insert(entry.id(), entry.taskId(), entry.eventType().name(),
                entry.fromStatus() == null ? null : entry.fromStatus().name(),
                entry.toStatus() == null ? null : entry.toStatus().name(),
                entry.blockingCountBefore(), entry.blockingCountAfter(), entry.createdAt());
        return entry;
    }

    @Override
    public PageResponse<TaskStateHistoryEntry> findByTaskId(UUID taskId, Instant from, Instant to, int page,
                                                            int size) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        // Newest first; sequence is the same-millisecond tiebreaker (mirrors the in-memory repository).
        Sort sort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("sequence"));
        Page<TaskStateHistoryEntity> result = jpaRepository.findByTaskIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                taskId, from == null ? OPEN_FROM : from, to == null ? OPEN_TO : to,
                PageRequest.of(page, size, sort));
        return new PageResponse<>(result.getContent().stream().map(PostgresTaskStateHistoryRepository::toDto).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    private static TaskStateHistoryEntry toDto(TaskStateHistoryEntity e) {
        return new TaskStateHistoryEntry(e.getId(), e.getTaskId(), e.getEventType(), e.getFromStatus(),
                e.getToStatus(), e.getBlockingCountBefore(), e.getBlockingCountAfter(), e.getCreatedAt());
    }
}
