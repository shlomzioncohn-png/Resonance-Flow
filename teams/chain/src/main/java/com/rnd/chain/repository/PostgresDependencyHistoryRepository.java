package com.rnd.chain.repository;

import com.rnd.chain.dto.DependencyChangeSource;
import com.rnd.chain.dto.DependencyHistoryEntry;
import com.rnd.chain.dto.PageResponse;
import com.rnd.chain.repository.entity.DependencyHistoryEntity;
import com.rnd.chain.repository.jpa.SpringDataDependencyHistoryJpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Postgres-backed {@link DependencyHistoryRepository}. Dedup on (source, sourceRef) is enforced
 * by {@code uq_dependency_history_source_source_ref} (V2 migration) via an insert-then-read-back
 * upsert. */
@Repository
public class PostgresDependencyHistoryRepository implements DependencyHistoryRepository {

    private final SpringDataDependencyHistoryJpaRepository jpaRepository;

    public PostgresDependencyHistoryRepository(SpringDataDependencyHistoryJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    @Transactional
    public DependencyHistoryEntry record(DependencyHistoryEntry entry) {
        Objects.requireNonNull(entry, "entry must not be null");
        jpaRepository.insertIgnoringConflict(entry.id(), entry.taskId(), entry.dependsOnTaskId(),
                entry.action().name(), entry.source().name(), entry.sourceRef(), entry.createdAt());
        if (entry.sourceRef() == null) {
            return entry;
        }
        // Read back whichever row now owns (source, sourceRef) - the new one, or a pre-existing one.
        return findBySourceAndSourceRef(entry.source(), entry.sourceRef())
                .orElseThrow(() -> new IllegalStateException("dependency history entry missing immediately "
                        + "after insert for source=" + entry.source() + " sourceRef=" + entry.sourceRef()));
    }

    @Override
    @Transactional
    public void delete(UUID entryId) {
        Objects.requireNonNull(entryId, "entryId must not be null");
        // deleteById() throws EmptyResultDataAccessException if absent - the interface requires
        // a no-op instead, so existence is checked first.
        if (jpaRepository.existsById(entryId)) {
            jpaRepository.deleteById(entryId);
        }
    }

    @Override
    public Optional<DependencyHistoryEntry> findBySourceAndSourceRef(DependencyChangeSource source, String sourceRef) {
        if (sourceRef == null) {
            return Optional.empty();
        }
        return jpaRepository.findBySourceAndSourceRef(source, sourceRef).map(PostgresDependencyHistoryRepository::toDto);
    }

    @Override
    public PageResponse<DependencyHistoryEntry> findByTaskId(UUID taskId, int page, int size) {
        // Newest first: createdAt descending, sequence descending as the same-millisecond
        // tiebreaker (mirrors InMemoryDependencyHistoryRepository's sequenceById/AtomicLong).
        Sort sort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("sequence"));
        Page<DependencyHistoryEntity> result = jpaRepository.findByTaskId(taskId, PageRequest.of(page, size, sort));
        return new PageResponse<>(result.getContent().stream().map(PostgresDependencyHistoryRepository::toDto).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    private static DependencyHistoryEntry toDto(DependencyHistoryEntity entity) {
        return DependencyHistoryEntry.builder()
                .id(entity.getId())
                .taskId(entity.getTaskId())
                .dependsOnTaskId(entity.getDependsOnTaskId())
                .action(entity.getAction())
                .source(entity.getSource())
                .sourceRef(entity.getSourceRef())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
