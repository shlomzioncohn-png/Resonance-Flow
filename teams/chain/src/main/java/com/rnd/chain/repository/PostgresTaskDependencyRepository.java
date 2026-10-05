package com.rnd.chain.repository;

import com.rnd.chain.dto.TaskDependency;
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
                .map(entity -> new TaskDependency(entity.getId().getTaskId(), entity.getId().getDependsOnTaskId()))
                .toList();
    }

    @Override
    public List<TaskDependency> findByDependsOnTaskId(UUID dependsOnTaskId) {
        Objects.requireNonNull(dependsOnTaskId, "dependsOnTaskId must not be null");
        return jpaRepository.findByIdDependsOnTaskId(dependsOnTaskId).stream()
                .map(entity -> new TaskDependency(entity.getId().getTaskId(), entity.getId().getDependsOnTaskId()))
                .toList();
    }

    /** Only the PK conflict is suppressed; FK/CHECK constraint violations still propagate. */
    @Override
    @Transactional
    public Optional<TaskDependency> save(TaskDependency dependency) {
        Objects.requireNonNull(dependency, "dependency must not be null");
        int inserted = jpaRepository.insertIgnoringConflict(dependency.taskId(), dependency.dependsOnTaskId());
        return inserted > 0 ? Optional.of(dependency) : Optional.empty();
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
        return jpaRepository.deleteByIdTaskIdAndIdDependsOnTaskId(taskId, dependsOnTaskId) > 0;
    }
}
