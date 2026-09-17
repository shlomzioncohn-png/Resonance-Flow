package com.rnd.chain.repository;

import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.repository.entity.TaskDependencyEntity;
import com.rnd.chain.repository.entity.TaskDependencyId;
import com.rnd.chain.repository.jpa.SpringDataTaskDependencyJpaRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.jpa.EntityManagerFactoryUtils;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PostgresTaskDependencyRepository implements TaskDependencyRepository {

    private final SpringDataTaskDependencyJpaRepository jpaRepository;
    private final EntityManager entityManager;

    public PostgresTaskDependencyRepository(SpringDataTaskDependencyJpaRepository jpaRepository,
                                             EntityManager entityManager) {
        this.jpaRepository = jpaRepository;
        this.entityManager = entityManager;
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

    /**
     * Atomic check-and-insert via a PK-conflict-as-signal: {@code entityManager.persist} followed
     * by an explicit {@code flush()} so the conflict surfaces here, not at the next unrelated
     * flush point (PERSISTENCE_PLAN.md §4) - no separate exists-check/race window.
     *
     * <p><b>Deliberately narrow catch.</b> Only a translated {@link DataIntegrityViolationException}
     * is treated as "edge already existed" and swallowed. The {@code chain.
     * fn_check_task_dependency_tasks_active} trigger (a soft-deleted referenced task, issue #243)
     * raises a distinct, uncategorized SQL exception - not a constraint violation - so it
     * translates to a different {@link DataAccessException} subtype and is never caught here; it
     * propagates to the caller instead of being silently swallowed as a no-op.
     */
    @Override
    @Transactional
    public Optional<TaskDependency> save(TaskDependency dependency) {
        Objects.requireNonNull(dependency, "dependency must not be null");
        TaskDependencyId id = new TaskDependencyId(dependency.taskId(), dependency.dependsOnTaskId());
        try {
            entityManager.persist(new TaskDependencyEntity(id));
            entityManager.flush();
            return Optional.of(dependency);
        } catch (PersistenceException e) {
            DataAccessException translated = EntityManagerFactoryUtils.convertJpaAccessExceptionIfPossible(e);
            if (translated instanceof DataIntegrityViolationException) {
                return Optional.empty();
            }
            throw translated != null ? translated : e;
        }
    }

    @Override
    public boolean delete(UUID taskId, UUID dependsOnTaskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(dependsOnTaskId, "dependsOnTaskId must not be null");
        return jpaRepository.deleteByIdTaskIdAndIdDependsOnTaskId(taskId, dependsOnTaskId) > 0;
    }
}
