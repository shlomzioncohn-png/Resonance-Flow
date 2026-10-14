package com.rnd.chain.repository;

import com.rnd.chain.dto.Task;
import com.rnd.chain.testsupport.ConcurrentTestRunner;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * In addition to the shared {@link TaskRepositoryContractTest} suite,
 * exercises behavior specific to this implementation's use of
 * {@link java.util.concurrent.ConcurrentHashMap}.
 */
class InMemoryTaskRepositoryTest extends TaskRepositoryContractTest {

    @Override
    protected TaskRepository createRepository(List<Task> seed) {
        return new InMemoryTaskRepository(seed);
    }

    @Test
    void update_isAtomicUnderConcurrentIncrement() throws InterruptedException {
        UUID taskId = UUID.randomUUID();
        InMemoryTaskRepository repository = new InMemoryTaskRepository(List.of(newTaskBuilder(taskId).build()));
        int threadCount = 20;
        int incrementsPerThread = 200;

        ConcurrentTestRunner.run(threadCount, () -> {
            for (int j = 0; j < incrementsPerThread; j++) {
                repository.update(taskId, InMemoryTaskRepositoryTest::incrementBlockingCount);
            }
        });

        assertThat(repository.findById(taskId).orElseThrow().getBlockingCount())
                .isEqualTo(threadCount * incrementsPerThread);
    }

    @Test
    void update_throwsAndLeavesTaskInPlace_whenMutatorReturnsNull() {
        // Regression test: update() is implemented via
        // ConcurrentHashMap.computeIfPresent, which removes the mapping
        // entirely if the remapping function returns null. Without a guard,
        // a mutator returning null would silently delete the task instead
        // of failing loudly - this proves the fix rejects that case and
        // that the task survives the attempt.
        UUID taskId = UUID.randomUUID();
        Task original = newTaskBuilder(taskId).build();
        InMemoryTaskRepository repository = new InMemoryTaskRepository(List.of(original));

        assertThatThrownBy(() -> repository.update(taskId, task -> null))
                .isInstanceOf(NullPointerException.class);

        Optional<Task> stillPresent = repository.findById(taskId);
        assertThat(stillPresent).contains(original);
    }

    @Test
    void findById_throwsNullPointerException_whenIdIsNull() {
        InMemoryTaskRepository repository = new InMemoryTaskRepository(List.of());

        assertThatThrownBy(() -> repository.findById(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("id");
    }

    @Test
    void findAllById_throwsNullPointerException_whenIdsIsNull() {
        InMemoryTaskRepository repository = new InMemoryTaskRepository(List.of());

        assertThatThrownBy(() -> repository.findAllById(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ids");
    }

    private static Task incrementBlockingCount(Task task) {
        return Task.builder()
                .id(task.getId())
                .parentTaskId(task.getParentTaskId())
                .status(task.getStatus())
                .blockingCount(task.getBlockingCount() + 1)
                .openConstraintCount(task.getOpenConstraintCount())
                .estimatedDurationMin(task.getEstimatedDurationMin())
                .score(task.getScore())
                .createdAt(task.getCreatedAt())
                .updatedAt(task.getUpdatedAt())
                .deletedAt(task.getDeletedAt())
                .build();
    }
}
