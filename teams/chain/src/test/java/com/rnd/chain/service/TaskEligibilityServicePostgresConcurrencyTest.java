package com.rnd.chain.service;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.exception.InvalidDependencyException;
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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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

    @Autowired
    private TaskEligibilityService service;

    @Autowired
    private DependencyEdgeService edgeService;

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

    private static final UUID TENANT_ID = UUID.randomUUID();

    private Task seedTask(UUID id) {
        Task task = Task.builder()
                .id(id)
                .tenantId(TENANT_ID)
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

        List<Runnable> jobs = blockerIds.stream()
                .<Runnable>map(blockerId -> () -> edgeService.addDependency(hubId, blockerId))
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

        for (UUID blockerId : blockerIds) {
            edgeService.addDependency(hubId, blockerId);
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

            runConcurrently(List.of(
                    () -> edgeService.addDependency(dependentId, blockerId),
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

    @Test
    void concurrentAddDependency_closingCycle_onlyOneSucceeds() throws InterruptedException {
        // With A -> B in place, B -> C and C -> A together close a cycle; the graph lock must make
        // the second writer's DFS see the first one's committed edge.
        int trials = 10;
        for (int i = 0; i < trials; i++) {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            UUID c = UUID.randomUUID();

            seedTask(a);
            seedTask(b);
            seedTask(c);
            edgeService.addDependency(a, b);

            AtomicInteger successes = new AtomicInteger();
            ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
            List<Runnable> jobs = List.of(
                    () -> attempt(() -> edgeService.addDependency(b, c), successes, failures),
                    () -> attempt(() -> edgeService.addDependency(c, a), successes, failures));
            runConcurrently(jobs);

            assertEquals(1, successes.get(), "trial " + i + ": exactly one of the two edges should be accepted");
            assertEquals(1, failures.size(), "trial " + i + ": exactly one of the two edges should be rejected");
            assertTrue(failures.peek() instanceof InvalidDependencyException,
                    "trial " + i + ": rejection should be a cycle error, got " + failures.peek());
            assertFalse(taskDependencyRepository.existsDependency(b, c)
                            && taskDependencyRepository.existsDependency(c, a),
                    "trial " + i + ": both edges were committed, closing the cycle A -> B -> C -> A");
        }
    }

    private static void attempt(Runnable action, AtomicInteger successes, ConcurrentLinkedQueue<Throwable> failures) {
        try {
            action.run();
            successes.incrementAndGet();
        } catch (RuntimeException e) {
            failures.add(e);
        }
    }
}
