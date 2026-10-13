package com.rnd.chain.service;

import com.rnd.chain.dto.Task;
import com.rnd.chain.exception.TaskNotFoundException;
import com.rnd.chain.repository.TaskRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

@Component
public class TaskCounterUpdater {

    private static final Logger log =
            LoggerFactory.getLogger(TaskCounterUpdater.class);

    private final TaskRepository taskRepository;
    private final SchedulingUpdateService schedulingUpdateService;

    public TaskCounterUpdater(
            TaskRepository taskRepository,
            SchedulingUpdateService schedulingUpdateService) {

        this.taskRepository = taskRepository;
        this.schedulingUpdateService = schedulingUpdateService;
    }

    public Task requireTask(UUID taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));
    }

    public static <T> T requireUpdated(
            Optional<T> updated,
            UUID taskId) {

        return updated.orElseThrow(
                () -> new TaskNotFoundException(taskId));
    }

    public static Task.Builder copyOf(Task task) {
        return Task.builder()
                .id(task.getId())
                .parentTaskId(task.getParentTaskId())
                .ownerId(task.getOwnerId())
                .tenantId(task.getTenantId())
                .status(task.getStatus())
                .isReady(task.isReady())
                .blockingCount(task.getBlockingCount())
                .openConstraintCount(task.getOpenConstraintCount())
                .estimatedDurationMin(task.getEstimatedDurationMin())
                .score(task.getScore())
                .createdAt(task.getCreatedAt())
                .updatedAt(task.getUpdatedAt())
                .deletedAt(task.getDeletedAt());
    }

    public static Task withCounters(
            Task task,
            int blockingCount,
            int openConstraintCount) {

        boolean isReady = blockingCount == 0
                && openConstraintCount == 0;

        if (task.getBlockingCount() == blockingCount
                && task.getOpenConstraintCount() == openConstraintCount
                && task.isReady() == isReady) {

            return task;
        }

        return copyOf(task)
                .blockingCount(blockingCount)
                .openConstraintCount(openConstraintCount)
                .isReady(isReady)
                .build();
    }

    public Task updateCounters(
            UUID taskId,
            UnaryOperator<Task> countersMutator) {

        AtomicReference<Task> previous = new AtomicReference<>();

        Task updated = requireUpdated(
                taskRepository.update(
                        taskId,
                        task -> {
                            previous.set(task);
                            return countersMutator.apply(task);
                        }),
                taskId);

        Task oldTask = previous.get();

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

        if (oldTask != null
                && oldTask.isReady() != updated.isReady()) {

            schedulingUpdateService
                    .publishSchedulingUpdate(updated);
        }

        return updated;
    }

    public void adjustBlockingCount(
            UUID taskId,
            boolean dependsOnIsDone,
            int delta) {

        int effectiveDelta = dependsOnIsDone ? 0 : delta;

        updateCounters(
                taskId,
                task -> withCounters(
                        task,
                        Math.max(
                                0,
                                task.getBlockingCount()
                                        + effectiveDelta),
                        task.getOpenConstraintCount()));
    }
}
