package com.rnd.chain.repository;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.testsupport.ConcurrentTestRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryTaskRepositoryTest {

    private final UUID taskId = UUID.randomUUID();

    private InMemoryTaskRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InMemoryTaskRepository(List.of(newTask(taskId, 0)));
    }

    // --- update() ------------------------------------------------------

    @Test
    void update_appliesMutatorAndPersistsResult() {
        Optional<Task> result = repository.update(taskId, task -> incrementBlockingCount(task));

        assertThat(result).isPresent();
        assertThat(result.get().getBlockingCount()).isEqualTo(1);
        assertThat(repository.findById(taskId).orElseThrow().getBlockingCount()).isEqualTo(1);
    }

    @Test
    void update_returnsEmpty_whenTaskDoesNotExist() {
        assertThat(repository.update(UUID.randomUUID(), UnaryOperator.identity())).isEmpty();
    }

    @Test
    void update_isAtomicUnderConcurrentIncrement() throws InterruptedException {
        int threadCount = 20;
        int incrementsPerThread = 200;

        ConcurrentTestRunner.run(threadCount, () -> {
            for (int j = 0; j < incrementsPerThread; j++) {
                repository.update(taskId, task -> incrementBlockingCount(task));
            }
        });

        assertThat(repository.findById(taskId).orElseThrow().getBlockingCount())
                .isEqualTo(threadCount * incrementsPerThread);
    }

    private static Task newTask(UUID id, int blockingCount) {
        return Task.builder()
                .id(id)
                .ownerId("owner")
                .status(TaskStatus.ACTIVE)
                .blockingCount(blockingCount)
                .openConstraintCount(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private static Task incrementBlockingCount(Task task) {
        return Task.builder()
                .id(task.getId())
                .parentTaskId(task.getParentTaskId())
                .ownerId(task.getOwnerId())
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
