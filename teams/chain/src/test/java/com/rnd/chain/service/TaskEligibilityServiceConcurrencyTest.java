package com.rnd.chain.service;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskTestFixtures;
import com.rnd.chain.repository.InMemoryTaskDependencyRepository;
import com.rnd.chain.repository.InMemoryTaskRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises TaskEligibilityService against the real in-memory repositories (not mocks) under
 * actual concurrent load, so lock-related races - like addDependency/removeDependency not being
 * mutually exclusive with the other counter-touching methods - actually get a chance to surface.
 */
class TaskEligibilityServiceConcurrencyTest {

    private static final int THREAD_COUNT = 16;

    /** Runs `task` on THREAD_COUNT threads simultaneously (released by a shared latch), waits for all to finish. */
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

        List<Task> allTasks = new ArrayList<>();
        allTasks.add(TaskTestFixtures.defaultTask(hubId).build());
        blockerIds.forEach(id -> allTasks.add(TaskTestFixtures.defaultTask(id).build()));

        InMemoryTaskRepository taskRepository = new InMemoryTaskRepository(allTasks);
        InMemoryTaskDependencyRepository dependencyRepository = new InMemoryTaskDependencyRepository(List.of());
        TaskEligibilityService service = new TaskEligibilityService(taskRepository, dependencyRepository);

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

        List<Task> allTasks = new ArrayList<>();
        allTasks.add(TaskTestFixtures.defaultTask(hubId).build());
        blockerIds.forEach(id -> allTasks.add(TaskTestFixtures.defaultTask(id).build()));

        InMemoryTaskRepository taskRepository = new InMemoryTaskRepository(allTasks);
        InMemoryTaskDependencyRepository dependencyRepository = new InMemoryTaskDependencyRepository(List.of());
        TaskEligibilityService service = new TaskEligibilityService(taskRepository, dependencyRepository);

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

    /**
     * PLAN.md Finding #9: a PR comment on this branch claimed a test with this name already
     * covered the race between adding a new edge to a target and that same target completing at
     * the same time, but no such test existed anywhere in the repo (confirmed by
     * {@code git grep} across the whole module and a full read of this file). This is that test.
     *
     * <p>The race: {@code addDependency(dependent, target)} and
     * {@code onTaskStatusChanged(target, DONE)} both read the target's DONE status under
     * {@code dependencyGraphLock} before deciding whether the new edge counts against
     * {@code blockingCount} - see the comments in both methods. Run many times (not just once)
     * because a lock-ordering bug here would only reproduce on some interleavings, and a single
     * pass could pass by luck even with the bug present.
     *
     * <p>Invariant under test: whichever order the two calls actually interleave in, the
     * dependent must never end up permanently stuck - i.e. it must end with
     * {@code blockingCount == 0} and {@code isReady == true}, since target is DONE by the time
     * both operations have completed either way.
     */
    @Test
    void concurrentAddDependency_raceWithTargetCompletion_neverLeavesDependentPermanentlyStuck() throws InterruptedException {
        int trials = 50;
        for (int trial = 0; trial < trials; trial++) {
            UUID dependentId = UUID.randomUUID();
            UUID targetId = UUID.randomUUID();

            InMemoryTaskRepository taskRepository = new InMemoryTaskRepository(
                    List.of(TaskTestFixtures.defaultTask(dependentId).build(), TaskTestFixtures.defaultTask(targetId).build()));
            InMemoryTaskDependencyRepository dependencyRepository =
                    new InMemoryTaskDependencyRepository(List.of());
            TaskEligibilityService service = new TaskEligibilityService(taskRepository, dependencyRepository);

            List<Runnable> jobs = List.of(
                    () -> service.addDependency(dependentId, targetId),
                    () -> service.onTaskStatusChanged(targetId, TaskStatus.DONE));

            runConcurrentPair(jobs);

            Task dependent = taskRepository.findById(dependentId).orElseThrow();
            assertEquals(0, dependent.getBlockingCount(),
                    "trial " + trial + ": dependent must not be left permanently stuck");
            assertTrue(dependent.isReady(),
                    "trial " + trial + ": dependent must become ready once target is DONE");
        }
    }

    /**
     * Same shape as runConcurrently, but sized for exactly two racing operations per trial
     * rather than THREAD_COUNT - a fresh two-thread pool per trial keeps each of the 50 trials
     * independent (no leftover state or thread reuse across trials).
     */
    private void runConcurrentPair(List<Runnable> tasks) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
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
        assertTrue(done.await(10, TimeUnit.SECONDS), "workers did not finish in time");
        pool.shutdown();
    }
}
