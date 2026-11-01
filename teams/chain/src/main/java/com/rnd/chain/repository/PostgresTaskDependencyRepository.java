package com.rnd.chain.repository;

import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.repository.entity.TaskDependencyEntity;
import com.rnd.chain.repository.jpa.SpringDataTaskDependencyJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PostgresTaskDependencyRepository implements TaskDependencyRepository {

    private final SpringDataTaskDependencyJpaRepository jpaRepository;

    public PostgresTaskDependencyRepository(SpringDataTaskDependencyJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public List<TaskDependency> findByTaskId(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        return jpaRepository.findByIdTaskId(taskId).stream()
                .map(PostgresTaskDependencyRepository::toDto)
                .toList();
    }

    @Override
    public List<TaskDependency> findByDependsOnTaskId(UUID dependsOnTaskId) {
        Objects.requireNonNull(dependsOnTaskId, "dependsOnTaskId must not be null");
        return jpaRepository.findByIdDependsOnTaskId(dependsOnTaskId).stream()
                .map(PostgresTaskDependencyRepository::toDto)
                .toList();
    }

    @Override
    public List<TaskDependency> findByTaskIds(List<UUID> taskIds) {
        if (taskIds == null || taskIds.isEmpty()) {
            return List.of();
        }

        return jpaRepository.findByIdTaskIdIn(taskIds).stream()
                .map(PostgresTaskDependencyRepository::toDto)
                .toList();
    }
    /**
     * Issue #445: upserts {@code confidence} even when the edge already exists (Mind's republish
     * semantics - see {@link SpringDataTaskDependencyJpaRepository#upsert}'s Javadoc), so this is
     * no longer a pure "PK conflict suppressed" no-op. {@code Optional.empty()} still means
     * specifically "not a new edge", not "nothing happened" - FK/CHECK/trigger violations still
     * propagate as before.
     */
    @Override
    @Transactional
    public Optional<TaskDependency> save(TaskDependency dependency) {
        Objects.requireNonNull(dependency, "dependency must not be null");
        boolean wasInserted = jpaRepository.upsert(
                dependency.taskId(), dependency.dependsOnTaskId(), dependency.confidence());
        return wasInserted ? Optional.of(dependency) : Optional.empty();
    }

    private static TaskDependency toDto(TaskDependencyEntity entity) {
        return new TaskDependency(
                entity.getId().getTaskId(), entity.getId().getDependsOnTaskId(), entity.getConfidence());
    }

    @Override
    public boolean existsDependency(UUID taskId, UUID dependsOnTaskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(dependsOnTaskId, "dependsOnTaskId must not be null");
        return jpaRepository.existsByIdTaskIdAndIdDependsOnTaskId(taskId, dependsOnTaskId);
    }

    @Override
    @Transactional
    public boolean delete(UUID taskId, UUID dependsOnTaskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(dependsOnTaskId, "dependsOnTaskId must not be null");
        boolean deleted = jpaRepository.deleteByIdTaskIdAndIdDependsOnTaskId(taskId, dependsOnTaskId) > 0;
        // The derived delete only schedules the removal; callers re-read the task_readiness view
        // through JDBC right afterwards, which Hibernate would not flush for, so they would
        // still see the edge.
        jpaRepository.flush();
        return deleted;
    }
}
