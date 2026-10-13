package com.rnd.chain.repository;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Behavior every {@link TaskRepository} implementation must satisfy, per the
 * contract documented on the interface - in particular soft-deleted tasks
 * being treated as not found everywhere, and the upsert/atomic-update
 * guarantees of {@link TaskRepository#save} and {@link TaskRepository#update}.
 * Subclasses only need to supply a repository instance; every test here runs
 * against every implementation.
 */
abstract class TaskRepositoryContractTest {

    protected abstract TaskRepository createRepository(List<Task> seed);

    protected static Task.Builder newTaskBuilder(UUID id) {
        LocalDateTime now = LocalDateTime.now();
        return Task.builder()
                .id(id)
                .status(TaskStatus.ACTIVE)
                .blockingCount(0)
                .openConstraintCount(0)
                .createdAt(now)
                .updatedAt(now);
    }

    // --- findById --------------------------------------------------------

    @Test
    void findById_returnsTask_whenItExists() {
        UUID id = UUID.randomUUID();
        TaskRepository repository = createRepository(List.of(newTaskBuilder(id).build()));

        assertThat(repository.findById(id)).contains(newTaskBuilder(id).build());
    }

    @Test
    void findById_returnsEmpty_whenTaskDoesNotExist() {
        TaskRepository repository = createRepository(List.of());

        assertThat(repository.findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void findById_returnsEmpty_whenTaskIsSoftDeleted() {
        UUID id = UUID.randomUUID();
        Task deleted = newTaskBuilder(id).deletedAt(LocalDateTime.now()).build();
        TaskRepository repository = createRepository(List.of(deleted));

        assertThat(repository.findById(id)).isEmpty();
    }

    // --- findAllById -------------------------------------------------------

    @Test
    void findAllById_returnsOnlyMatchingNonDeletedTasks() {
        UUID found = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        UUID deleted = UUID.randomUUID();
        TaskRepository repository = createRepository(List.of(
                newTaskBuilder(found).build(),
                newTaskBuilder(deleted).deletedAt(LocalDateTime.now()).build()));

        List<Task> result = repository.findAllById(List.of(found, missing, deleted));

        assertThat(result).extracting(Task::getId).containsExactly(found);
    }

    @Test
    void findAllById_returnsEmptyList_whenNoIdsMatch() {
        TaskRepository repository = createRepository(List.of());

        assertThat(repository.findAllById(List.of(UUID.randomUUID()))).isEmpty();
    }

    // --- findByParentTaskId ------------------------------------------------

    @Test
    void findByParentTaskId_returnsDirectChildren() {
        UUID parentId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        TaskRepository repository = createRepository(List.of(
                newTaskBuilder(childId).parentTaskId(parentId).build(),
                newTaskBuilder(otherId).build()));

        List<Task> children = repository.findByParentTaskId(parentId);

        assertThat(children).extracting(Task::getId).containsExactly(childId);
    }

    @Test
    void findByParentTaskId_excludesSoftDeletedChildren() {
        UUID parentId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        TaskRepository repository = createRepository(List.of(
                newTaskBuilder(childId).parentTaskId(parentId).deletedAt(LocalDateTime.now()).build()));

        assertThat(repository.findByParentTaskId(parentId)).isEmpty();
    }

    @Test
    void findByParentTaskId_returnsEmptyList_whenTaskHasNoChildren() {
        TaskRepository repository = createRepository(List.of());

        assertThat(repository.findByParentTaskId(UUID.randomUUID())).isEmpty();
    }

    // --- save ----------------------------------------------------------

    @Test
    void save_insertsNewTask() {
        UUID id = UUID.randomUUID();
        TaskRepository repository = createRepository(List.of());

        repository.save(newTaskBuilder(id).build());

        assertThat(repository.findById(id)).isPresent();
    }

    @Test
    void save_overwritesExistingTaskWithSameId() {
        UUID id = UUID.randomUUID();
        TaskRepository repository = createRepository(List.of(newTaskBuilder(id).blockingCount(0).build()));

        repository.save(newTaskBuilder(id).blockingCount(5).build());

        assertThat(repository.findById(id).orElseThrow().getBlockingCount()).isEqualTo(5);
    }
    // --- insertIfAbsent -----------------------------------------------------
    @Test
void insertIfAbsent_insertsTask_whenTaskDoesNotExist() {
    UUID id = UUID.randomUUID();
    TaskRepository repository = createRepository(List.of());

    boolean inserted = repository.insertIfAbsent(
            newTaskBuilder(id)
                    .status(TaskStatus.PENDING)
                    .blockingCount(0)
                    .build()
    );

    assertThat(inserted).isTrue();

    Task persisted = repository.findById(id).orElseThrow();
    assertThat(persisted.getStatus()).isEqualTo(TaskStatus.PENDING);
    assertThat(persisted.getBlockingCount()).isZero();
}
@Test
void insertIfAbsent_doesNotOverwriteExistingTaskWithSameId() {
    UUID id = UUID.randomUUID();

    Task existing = newTaskBuilder(id)
            .status(TaskStatus.ACTIVE)
            .blockingCount(3)
            .openConstraintCount(1)
            .isReady(false)
            .build();

    TaskRepository repository = createRepository(List.of(existing));

    Task attemptedReplacement = newTaskBuilder(id)
            .status(TaskStatus.DONE)
            .blockingCount(0)
            .openConstraintCount(0)
            .isReady(true)
            .build();

    boolean inserted = repository.insertIfAbsent(attemptedReplacement);

    assertThat(inserted).isFalse();

    Task persisted = repository.findById(id).orElseThrow();

    assertThat(persisted.getStatus()).isEqualTo(TaskStatus.ACTIVE);
    assertThat(persisted.getBlockingCount()).isEqualTo(3);
    assertThat(persisted.getOpenConstraintCount()).isEqualTo(1);
    assertThat(persisted.isReady()).isFalse();
}

    // --- update ----------------------------------------------------------

    @Test
    void update_appliesMutatorAndPersistsResult() {
        UUID id = UUID.randomUUID();
        TaskRepository repository = createRepository(List.of(newTaskBuilder(id).blockingCount(0).build()));

        Optional<Task> result = repository.update(id, task ->
                Task.builder()
                        .id(task.getId())
                        .status(task.getStatus())
                        .blockingCount(task.getBlockingCount() + 1)
                        .openConstraintCount(task.getOpenConstraintCount())
                        .createdAt(task.getCreatedAt())
                        .updatedAt(task.getUpdatedAt())
                        .build());

        assertThat(result).isPresent();
        assertThat(result.get().getBlockingCount()).isEqualTo(1);
        assertThat(repository.findById(id).orElseThrow().getBlockingCount()).isEqualTo(1);
    }

    @Test
    void update_returnsEmpty_whenTaskDoesNotExist() {
        TaskRepository repository = createRepository(List.of());

        assertThat(repository.update(UUID.randomUUID(), UnaryOperator.identity())).isEmpty();
    }

    @Test
    void update_leavesSoftDeletedTaskUntouched() {
        // Regression test for the bug fixed in 32402a7 ("Fix update()
        // mutating soft-deleted tasks"): update() must not apply the
        // mutator at all to a soft-deleted task. findById() would report
        // "not found" for a soft-deleted task either way, so a mutator
        // that throws is used here to prove it was never invoked - a
        // mutator that merely changes a field wouldn't be able to tell
        // the difference through the public API.
        UUID id = UUID.randomUUID();
        Task deleted = newTaskBuilder(id).deletedAt(LocalDateTime.now()).build();
        TaskRepository repository = createRepository(List.of(deleted));

        Optional<Task> result = repository.update(id, task -> {
            throw new AssertionError("mutator must not be applied to a soft-deleted task");
        });

        assertThat(result).isEmpty();
    }
}
