package com.rnd.chain.service;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.repository.ConstraintRepository;
import com.rnd.chain.repository.InMemoryTaskDependencyRepository;
import com.rnd.chain.repository.InMemoryTaskRepository;
import org.junit.jupiter.api.Test;

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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Exercises TaskEligibilityService against the real in-memory repositories (not mocks) under
 * actual concurrent load, so lock-related races - like addDependency/removeDependency not being
 * mutually exclusive with the other counter-touching methods - actually get a chance to surface.
 */
class TaskEligibilityServiceConcurrencyTest {

    private static final int THREAD_COUNT = 16;

    private Task buildTask(UUID id) {
        return Task.builder()
                .id(id)
                .ownerId("owner")
                .status(TaskStatus.ACTIVE)
                .isReady(false)
                .blockingCount(0)
                .openConstraintCount(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

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
        allTasks.add(buildTask(hubId));
        blockerIds.forEach(id -> allTasks.add(buildTask(id)));

        InMemoryTaskRepository taskRepository = new InMemoryTaskRepository(allTasks);
        InMemoryTaskDependencyRepository dependencyRepository = new InMemoryTaskDependencyRepository(List.of());
        // Not exercised by this class - none of these tests touch constraint methods.
        TaskEligibilityService service =
                new TaskEligibilityService(taskRepository, dependencyRepository, mock(ConstraintRepository.class));

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
        allTasks.add(buildTask(hubId));
        blockerIds.forEach(id -> allTasks.add(buildTask(id)));

        InMemoryTaskRepository taskRepository = new InMemoryTaskRepository(allTasks);
        InMemoryTaskDependencyRepository dependencyRepository = new InMemoryTaskDependencyRepository(List.of());
        // Not exercised by this class - none of these tests touch constraint methods.
        TaskEligibilityService service =
                new TaskEligibilityService(taskRepository, dependencyRepository, mock(ConstraintRepository.class));

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
        // Regression test for the two Critical findings together: addDependency reading the
        // target's DONE status races against onTaskStatusChanged flipping it, for many trials,
        // to catch a rare interleaving a single run could miss.
        int trials = 50;
        for (int i = 0; i < trials; i++) {
            UUID dependentId = UUID.randomUUID();
            UUID blockerId = UUID.randomUUID();

            InMemoryTaskRepository taskRepository = new InMemoryTaskRepository(
                    List.of(buildTask(dependentId), buildTask(blockerId)));
            InMemoryTaskDependencyRepository dependencyRepository = new InMemoryTaskDependencyRepository(List.of());
            // Not exercised by this class - none of these tests touch constraint methods.
        TaskEligibilityService service =
                new TaskEligibilityService(taskRepository, dependencyRepository, mock(ConstraintRepository.class));

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
