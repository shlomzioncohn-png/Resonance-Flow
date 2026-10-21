package com.rnd.chain.service;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskReadinessSnapshot;
import com.rnd.chain.exception.TaskNotFoundException;
import com.rnd.chain.repository.TaskReadinessRepository;
import com.rnd.chain.repository.TaskRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

@Slf4j
@RequiredArgsConstructor
@Component
public class TaskCounterUpdater {

    private final TaskRepository taskRepository;
    private final PriorityScoreService priorityScoreService;
    private final TaskReadinessRepository taskReadinessRepository;

    /**
     * Stored counters are still maintained during this migration stage.
     *
     * Readiness decisions must not rely on the isReady value produced here.
     * task_readiness is the authoritative source for such decisions.
     */
    public static Task withCounters(
            Task task,
            int blockingCount,
            int openConstraintCount) {

        boolean isReady =
                blockingCount == 0
                        && openConstraintCount == 0;

        if (task.getBlockingCount() == blockingCount
                && task.getOpenConstraintCount() == openConstraintCount
                && task.isReady() == isReady) {

            return task;
        }

        return task.toBuilder()
                .blockingCount(blockingCount)
                .openConstraintCount(openConstraintCount)
                .isReady(isReady)
                .build();
    }

    /**
     * Compatibility overload for callers that only mutate the stored counters.
     *
     * The View state does not change as a result of changing the stored
     * compatibility columns themselves, so the same authoritative View
     * snapshot is used before and after.
     */
    @Transactional
    public Task updateCounters(
            UUID taskId,
            UnaryOperator<Task> countersMutator) {

        TaskReadinessSnapshot readiness =
                requireReadinessSnapshot(taskId);

        return updateCounters(
                taskId,
                countersMutator,
                readiness,
                readiness);
    }

    /**
     * Compatibility overload for maintaining the stored blocking counter.
     *
     * Decisions about readiness/publishing are still made from task_readiness.
     */
    @Transactional
    public void adjustBlockingCount(
            UUID taskId,
            boolean dependsOnIsDone,
            int delta) {

        TaskReadinessSnapshot readiness =
                requireReadinessSnapshot(taskId);

        adjustBlockingCount(
                taskId,
                dependsOnIsDone,
                delta,
                readiness,
                readiness);
    }

    /**
     * Updates the stored compatibility counters while using authoritative
     * before/after View snapshots for all readiness and publish decisions.
     */
    @Transactional
    public Task updateCounters(
            UUID taskId,
            UnaryOperator<Task> countersMutator,
            TaskReadinessSnapshot beforeReadiness,
            TaskReadinessSnapshot afterReadiness) {

        Objects.requireNonNull(
                beforeReadiness,
                "beforeReadiness must not be null");

        Objects.requireNonNull(
                afterReadiness,
                "afterReadiness must not be null");

        AtomicReference<Task> previous =
                new AtomicReference<>();

        Task updated =
                taskRepository.update(
                                taskId,
                                task -> {

                                    previous.set(task);

                                    return countersMutator.apply(
                                            task);
                                })
                        .orElseThrow(
                                () -> new TaskNotFoundException(taskId));

        Task oldTask =
                previous.get();

        if (updated != oldTask) {

            log.info(
                    "Task {} eligibility updated: "
                            + "blockingCount={}, "
                            + "openConstraintCount={}, "
                            + "isReady={}",
                    taskId,
                    updated.getBlockingCount(),
                    updated.getOpenConstraintCount(),
                    updated.isReady());
        }

        return priorityScoreService.recomputePriorityScoreAndPublish(
                taskId,
                oldTask,
                updated,
                beforeReadiness,
                afterReadiness);
    }

    /**
     * Maintains the stored blocking counter while readiness/publishing is
     * decided exclusively from the supplied View snapshots.
     */
    @Transactional
    public void adjustBlockingCount(
            UUID taskId,
            boolean dependsOnIsDone,
            int delta,
            TaskReadinessSnapshot beforeReadiness,
            TaskReadinessSnapshot afterReadiness) {

        int effectiveDelta =
                dependsOnIsDone
                        ? 0
                        : delta;

        updateCounters(
                taskId,
                task -> withCounters(
                        task,
                        Math.max(
                                0,
                                task.getBlockingCount()
                                        + effectiveDelta),
                        task.getOpenConstraintCount()),
                beforeReadiness,
                afterReadiness);
    }

    private TaskReadinessSnapshot requireReadinessSnapshot(
            UUID taskId) {

        TaskReadinessSnapshot snapshot =
                taskReadinessRepository
                        .findByIds(
                                List.of(taskId))
                        .get(taskId);

        if (snapshot == null) {

            throw new IllegalStateException(
                    "No task_readiness row found for task "
                            + taskId);
        }

        return snapshot;
    }
}