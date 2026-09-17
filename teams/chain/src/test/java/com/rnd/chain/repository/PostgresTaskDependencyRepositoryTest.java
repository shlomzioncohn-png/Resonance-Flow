package com.rnd.chain.repository;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.repository.entity.TaskDependencyEntity;
import com.rnd.chain.repository.entity.TaskDependencyId;
import com.rnd.chain.repository.jpa.SpringDataTaskDependencyJpaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers PERSISTENCE_PLAN.md §7's five dependency-edge cases against real PostgreSQL. The two
 * DataIntegrityViolation cases and the soft-deleted-trigger case each end their test method with
 * the exception assertion itself - Postgres marks a transaction "aborted" after any constraint/
 * trigger error, so no further statement is attempted in the same test method afterward; the
 * shared @Transactional rollback (the one statement Postgres still allows) is what cleans up.
 */
@SpringBootTest
@Transactional
class PostgresTaskDependencyRepositoryTest {

    @Autowired
    private PostgresTaskRepository postgresTaskRepository;

    @Autowired
    private PostgresTaskDependencyRepository postgresTaskDependencyRepository;

    @Autowired
    private SpringDataTaskDependencyJpaRepository jpaRepository;

    private Task seedTask() {
        UUID id = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        Task task = Task.builder()
                .id(id).ownerId("owner").status(TaskStatus.ACTIVE)
                .blockingCount(0).openConstraintCount(0)
                .createdAt(now).updatedAt(now).build();
        return postgresTaskRepository.save(task);
    }

    @Test
    void saveThenFind_returnsEdgeFromBothDirections() {
        Task task = seedTask();
        Task dependsOn = seedTask();

        Optional<TaskDependency> saved = postgresTaskDependencyRepository.save(
                new TaskDependency(task.getId(), dependsOn.getId()));

        assertThat(saved).contains(new TaskDependency(task.getId(), dependsOn.getId()));
        assertThat(postgresTaskDependencyRepository.findByTaskId(task.getId()))
                .containsExactly(new TaskDependency(task.getId(), dependsOn.getId()));
        assertThat(postgresTaskDependencyRepository.findByDependsOnTaskId(dependsOn.getId()))
                .containsExactly(new TaskDependency(task.getId(), dependsOn.getId()));
    }

    @Test
    void save_isIdempotent_whenEdgeAlreadyExists() {
        Task task = seedTask();
        Task dependsOn = seedTask();
        TaskDependency dependency = new TaskDependency(task.getId(), dependsOn.getId());

        assertThat(postgresTaskDependencyRepository.save(dependency)).contains(dependency);
        // Checked before the conflicting second call: Postgres marks the transaction "aborted"
        // after the PK-violation below, so no further query can run in this method afterward.
        assertThat(postgresTaskDependencyRepository.findByTaskId(task.getId())).hasSize(1);

        assertThat(postgresTaskDependencyRepository.save(dependency)).isEmpty();
    }

    @Test
    void save_throwsDataIntegrityViolation_whenReferencedTaskDoesNotExist() {
        TaskDependencyId id = new TaskDependencyId(UUID.randomUUID(), UUID.randomUUID());

        // Bypasses PostgresTaskDependencyRepository.save() directly, per PERSISTENCE_PLAN.md §7 -
        // this proves the FK constraint itself, independent of the adapter's dedup-catch logic.
        assertThatThrownBy(() -> jpaRepository.saveAndFlush(new TaskDependencyEntity(id)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void save_throwsDataIntegrityViolation_whenSelfDependency() {
        Task task = seedTask();
        TaskDependencyId id = new TaskDependencyId(task.getId(), task.getId());

        assertThatThrownBy(() -> jpaRepository.saveAndFlush(new TaskDependencyEntity(id)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void save_throwsException_whenReferencedTaskIsSoftDeleted() {
        Task blocker = seedTask();
        postgresTaskRepository.update(blocker.getId(), t ->
                Task.builder()
                        .id(t.getId()).ownerId(t.getOwnerId()).status(t.getStatus())
                        .blockingCount(t.getBlockingCount()).openConstraintCount(t.getOpenConstraintCount())
                        .createdAt(t.getCreatedAt()).updatedAt(t.getUpdatedAt())
                        .deletedAt(LocalDateTime.now())
                        .build());
        Task task = seedTask();

        // The §3 trigger's RAISE EXCEPTION is not a constraint violation - must not be swallowed
        // as "already exists" the way a DataIntegrityViolationException is.
        assertThatThrownBy(() -> postgresTaskDependencyRepository.save(
                new TaskDependency(task.getId(), blocker.getId())))
                .isInstanceOf(DataAccessException.class)
                .isNotInstanceOf(DataIntegrityViolationException.class);
    }
}
