package com.rnd.chain.service;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.repository.PostgresTaskDependencyRepository;
import com.rnd.chain.repository.PostgresTaskRepository;
import com.rnd.chain.repository.jpa.SpringDataTaskJpaRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Same scenarios as {@link TaskEligibilityServiceConcurrencyTest}, run against the real
 * Postgres-backed repositories instead of the in-memory pair. Deliberately NOT @Transactional:
 * each thread must open its own independent transaction/connection for TaskRepository#update's
 * pessimistic row lock to actually contend for real, which is the whole point of this test (a
 * shared test-transaction would serialize everything trivially and prove nothing). Rows are
 * cleaned up manually in @AfterEach instead.
 */
@Tag("postgres")
@SpringBootTest
class TaskEligibilityServicePostgresConcurrencyTest {

    private static final int THREAD_COUNT = 16;

    @Autowired
    private PostgresTaskRepository taskRepository;

    @Autowired
    private PostgresTaskDependencyRepository taskDependencyRepository;

    // TaskRepository's port interface has no hard-delete (only soft-delete via update()) - the
    // JPA repository is used directly here, test-only, purely for cleanup.
    @Autowired
    private SpringDataTaskJpaRepository jpaTaskRepository;

    private final List<UUID> createdTaskIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        createdTaskIds.forEach(id -> taskDependencyRepository.findByTaskId(id)
                .forEach(edge -> taskDependencyRepository.delete(edge.taskId(), edge.dependsOnTaskId())));
        jpaTaskRepository.deleteAllById(createdTaskIds);
        createdTaskIds.clear();
    }

    private Task seedTask(UUID id) {
        Task task = Task.builder()
                .id(id)
                .ownerId("owner")
                .status(TaskStatus.ACTIVE)
                .isReady(false)
                .blockingCount(0)
                .openConstraintCount(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        createdTaskIds.add(id);
        return taskRepository.save(task);
    }

    /** Runs `tasks` on THREAD_COUNT threads simultaneously (released by a shared latch), waits for all to finish. */
    private void runConcurrently(List<Runnable> tasks) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(THREAD_COUNT);
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(tasks.size());

        for (Runnable task : tasks) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    task.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS), "workers failed to start");
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "workers did not finish in time");
        pool.shutdown();
    }

    @Test
    void concurrentAddDependency_onSameTarget_noLostUpdates() throws InterruptedException {
        UUID hubId = UUID.randomUUID();
        List<UUID> blockerIds = IntStream.range(0, THREAD_COUNT)
                .mapToObj(i -> UUID.randomUUID())
                .collect(Collectors.toList());

        seedTask(hubId);
        blockerIds.forEach(this::seedTask);

        TaskEligibilityService service = new TaskEligibilityService(taskRepository, taskDependencyRepository);

        List<Runnable> jobs = blockerIds.stream()
                .<Runnable>map(blockerId -> () -> service.addDependency(hubId, blockerId))
                .collect(Collectors.toList());

        runConcurrently(jobs);

        Task hub = taskRepository.findById(hubId).orElseThrow();
        assertEquals(THREAD_COUNT, hub.getBlockingCount(), "every concurrent addDependency must be counted exactly once");
        assertFalse(hub.isReady());
    }

    @Test
    void concurrentCompletion_ofAllBlockers_dependentBecomesReady() throws InterruptedException {
        UUID hubId = UUID.randomUUID();
        List<UUID> blockerIds = IntStream.range(0, THREAD_COUNT)
                .mapToObj(i -> UUID.randomUUID())
                .collect(Collectors.toList());

        seedTask(hubId);
        blockerIds.forEach(this::seedTask);

        TaskEligibilityService service = new TaskEligibilityService(taskRepository, taskDependencyRepository);

        for (UUID blockerId : blockerIds) {
            service.addDependency(hubId, blockerId);
        }
        assertEquals(THREAD_COUNT, taskRepository.findById(hubId).orElseThrow().getBlockingCount());

        List<Runnable> jobs = blockerIds.stream()
                .<Runnable>map(blockerId -> () -> service.onTaskStatusChanged(blockerId, TaskStatus.DONE))
                .collect(Collectors.toList());

        runConcurrently(jobs);

        Task hub = taskRepository.findById(hubId).orElseThrow();
        assertEquals(0, hub.getBlockingCount(), "every concurrent completion must be counted exactly once");
        assertTrue(hub.isReady());
    }

    @Test
    void concurrentAddDependency_raceWithTargetCompletion_neverLeavesDependentPermanentlyStuck() throws InterruptedException {
        // Regression test for two Critical findings together: addDependency reading the target's
        // DONE status races against onTaskStatusChanged flipping it. Fewer trials than the
        // in-memory version (10 vs 50) - each trial round-trips real Postgres transactions.
        int trials = 10;
        for (int i = 0; i < trials; i++) {
            UUID dependentId = UUID.randomUUID();
            UUID blockerId = UUID.randomUUID();

            seedTask(dependentId);
            seedTask(blockerId);
            TaskEligibilityService service = new TaskEligibilityService(taskRepository, taskDependencyRepository);

            runConcurrently(List.of(
                    () -> service.addDependency(dependentId, blockerId),
                    () -> service.onTaskStatusChanged(blockerId, TaskStatus.DONE)));

            Task dependent = taskRepository.findById(dependentId).orElseThrow();
            Task blocker = taskRepository.findById(blockerId).orElseThrow();

            if (blocker.getStatus() == TaskStatus.DONE) {
                assertEquals(0, dependent.getBlockingCount(),
                        "trial " + i + ": blocker finished DONE but dependent is still counting it as blocking");
                assertTrue(dependent.isReady(), "trial " + i + ": dependent should be ready once its only blocker is DONE");
            }
        }
    }
}
