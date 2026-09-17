package com.rnd.chain.repository;

import com.rnd.chain.dto.Task;
import com.rnd.chain.repository.entity.TaskEntity;
import com.rnd.chain.repository.jpa.SpringDataTaskJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

/** Postgres-backed {@link TaskRepository}. Maps {@link TaskEntity} <-> {@link Task} here only -
 * neither type crosses this class's boundary in the other direction. */
@Repository
public class PostgresTaskRepository implements TaskRepository {

    private final SpringDataTaskJpaRepository jpaRepository;

    public PostgresTaskRepository(SpringDataTaskJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Optional<Task> findById(UUID id) {
        Objects.requireNonNull(id, "id must not be null");
        return jpaRepository.findByIdAndDeletedAtIsNull(id).map(PostgresTaskRepository::toDto);
    }

    @Override
    public List<Task> findAllById(List<UUID> ids) {
        Objects.requireNonNull(ids, "ids must not be null");
        return jpaRepository.findByIdInAndDeletedAtIsNull(ids).stream()
                .map(PostgresTaskRepository::toDto)
                .toList();
    }

    @Override
    public List<Task> findByParentTaskId(UUID parentTaskId) {
        if (parentTaskId == null) {
            return List.of();
        }
        return jpaRepository.findByParentTaskIdAndDeletedAtIsNull(parentTaskId).stream()
                .map(PostgresTaskRepository::toDto)
                .toList();
    }

    @Override
    public Task save(Task task) {
        Objects.requireNonNull(task, "task must not be null");
        jpaRepository.upsert(task.getId(), task.getParentTaskId(), task.getOwnerId(),
                task.getStatus().name(), task.isReady(), task.getBlockingCount(),
                task.getOpenConstraintCount(), task.getEstimatedDurationMin(), task.getScore(),
                task.getCreatedAt(), task.getUpdatedAt(), task.getDeletedAt());
        // @Modifying is a row-count-only native statement (no RETURNING) - the read-back is the
        // decided way this method returns the persisted Task (PERSISTENCE_PLAN.md §4).
        return jpaRepository.findByIdAndDeletedAtIsNull(task.getId())
                .map(PostgresTaskRepository::toDto)
                .orElseThrow(() -> new IllegalStateException("Task " + task.getId() + " missing immediately after save"));
    }

    /** Pessimistic row lock is the sole correctness mechanism here - it alone serializes every
     * update() call for a given id (PERSISTENCE_PLAN.md §4). Must run inside a transaction so the
     * lock is held from findByIdForUpdate through the dirty-checked flush at commit. */
    @Override
    @Transactional
    public Optional<Task> update(UUID id, UnaryOperator<Task> mutator) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(mutator, "mutator must not be null");

        Optional<TaskEntity> locked = jpaRepository.findByIdForUpdate(id);
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        TaskEntity entity = locked.get();
        if (entity.getDeletedAt() != null) {
            // Mutator must not be invoked at all against a soft-deleted task - matches
            // InMemoryTaskRepository/TaskRepositoryContractTest#update_leavesSoftDeletedTaskUntouched.
            return Optional.empty();
        }

        Task current = toDto(entity);
        Task updated = Objects.requireNonNull(mutator.apply(current), "mutator must not return null");
        copyOnto(entity, updated);

        return Optional.of(toDto(entity)).filter(t -> t.getDeletedAt() == null);
    }

    private static Task toDto(TaskEntity entity) {
        return Task.builder()
                .id(entity.getId())
                .parentTaskId(entity.getParentTaskId())
                .ownerId(entity.getOwnerId())
                .status(entity.getStatus())
                .isReady(entity.isReady())
                .blockingCount(entity.getBlockingCount())
                .openConstraintCount(entity.getOpenConstraintCount())
                .estimatedDurationMin(entity.getEstimatedDurationMin())
                .score(entity.getScore())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .deletedAt(entity.getDeletedAt())
                .build();
    }

    /** Copies {@code updated}'s fields onto the already-managed {@code entity} instance (not a
     * fresh save()) so Hibernate's dirty checking emits the UPDATE at commit. */
    private static void copyOnto(TaskEntity entity, Task updated) {
        entity.setParentTaskId(updated.getParentTaskId());
        entity.setOwnerId(updated.getOwnerId());
        entity.setStatus(updated.getStatus());
        entity.setReady(updated.isReady());
        entity.setBlockingCount(updated.getBlockingCount());
        entity.setOpenConstraintCount(updated.getOpenConstraintCount());
        entity.setEstimatedDurationMin(updated.getEstimatedDurationMin());
        entity.setScore(updated.getScore());
        entity.setCreatedAt(updated.getCreatedAt());
        entity.setUpdatedAt(updated.getUpdatedAt());
        entity.setDeletedAt(updated.getDeletedAt());
    }
}
