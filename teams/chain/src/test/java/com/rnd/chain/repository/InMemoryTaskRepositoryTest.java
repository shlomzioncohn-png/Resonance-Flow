package com.rnd.chain.repository;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryTaskRepositoryTest {

    private final UUID taskId = UUID.randomUUID();

    private InMemoryTaskRepository repository;

    @BeforeEach
    void setUp() {
        repository = new InMemoryTaskRepository(List.of(newTask(taskId, 0)));
    }

    // --- isolation (defensive copies) --------------------------------

    @Test
    void findById_returnsACopy_mutatingItDoesNotAffectStoredTask() {
        Task fetched = repository.findById(taskId).orElseThrow();

        fetched.setBlockingCount(999);

        assertThat(repository.findById(taskId).orElseThrow().getBlockingCount()).isZero();
    }

    @Test
    void save_storesACopy_mutatingTheOriginalAfterSaveDoesNotAffectStoredTask() {
        Task task = newTask(UUID.randomUUID(), 0);

        repository.save(task);
        task.setBlockingCount(999);

        assertThat(repository.findById(task.getId()).orElseThrow().getBlockingCount()).isZero();
    }

    @Test
    void findById_calledTwice_returnsIndependentInstances() {
        Task first = repository.findById(taskId).orElseThrow();
        Task second = repository.findById(taskId).orElseThrow();

        first.setBlockingCount(999);

        assertThat(second.getBlockingCount()).isZero();
    }

    // --- update() ------------------------------------------------------

    @Test
    void update_appliesMutatorAndPersistsResult() {
        Optional<Task> result = repository.update(taskId, task -> {
            task.setBlockingCount(task.getBlockingCount() + 1);
            return task;
        });

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
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                ready.countDown();
                await(start);
                for (int j = 0; j < incrementsPerThread; j++) {
                    repository.update(taskId, task -> {
                        task.setBlockingCount(task.getBlockingCount() + 1);
                        return task;
                    });
                }
                done.countDown();
            });
        }

        ready.await(5, TimeUnit.SECONDS);
        start.countDown();
        done.await(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(repository.findById(taskId).orElseThrow().getBlockingCount())
                .isEqualTo(threadCount * incrementsPerThread);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private static Task newTask(UUID id, int blockingCount) {
        return Task.builder()
                .id(id)
                .ownerId("owner")
                .status(TaskStatus.ACTIVE)
                .isReady(true)
                .blockingCount(blockingCount)
                .openConstraintCount(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }
}
