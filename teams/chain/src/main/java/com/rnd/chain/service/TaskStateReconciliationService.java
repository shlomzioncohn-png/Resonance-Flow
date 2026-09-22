package com.rnd.chain.service;

import com.rnd.chain.dto.Constraint;
import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.exception.TaskNotFoundException;
import com.rnd.chain.repository.ConstraintRepository;
import com.rnd.chain.repository.TaskDependencyRepository;
import com.rnd.chain.repository.TaskRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class TaskStateReconciliationService {

    private final TaskRepository taskRepository;
    private final TaskDependencyRepository taskDependencyRepository;
    private final ConstraintRepository constraintRepository;
    private static final Logger log = LoggerFactory.getLogger(TaskStateReconciliationService.class);

    public TaskStateReconciliationService(
            TaskRepository taskRepository,
            TaskDependencyRepository taskDependencyRepository,
            ConstraintRepository constraintRepository) {
        this.taskRepository = taskRepository;
        this.taskDependencyRepository = taskDependencyRepository;
        this.constraintRepository = constraintRepository;
    }

   public boolean reconcileTaskState(UUID taskId) {

    Task task = taskRepository.findById(taskId)
            .orElseThrow(() -> new TaskNotFoundException(taskId));

    int actualBlockingCount = calculateBlockingCount(taskId);
    int actualOpenConstraintCount = calculateOpenConstraintCount(taskId);

    boolean actualIsReady =
            actualBlockingCount == 0 && actualOpenConstraintCount == 0;

    boolean hasInconsistency =
            task.getBlockingCount() != actualBlockingCount ||
            task.getOpenConstraintCount() != actualOpenConstraintCount ||
            task.isReady() != actualIsReady;

    if (!hasInconsistency) {
        log.debug("Task {} reconciliation found no inconsistency", taskId);
        return false;
    }

    log.warn(
            "Task {} reconciliation found inconsistency: " +
                    "storedBlockingCount={}, actualBlockingCount={}, " +
                    "storedOpenConstraintCount={}, actualOpenConstraintCount={}, " +
                    "storedReady={}, actualReady={}",
            taskId,
            task.getBlockingCount(),
            actualBlockingCount,
            task.getOpenConstraintCount(),
            actualOpenConstraintCount,
            task.isReady(),
            actualIsReady
    );

    taskRepository.update(taskId, currentTask ->
            Task.builder()
                    .id(currentTask.getId())
                    .parentTaskId(currentTask.getParentTaskId())
                    .ownerId(currentTask.getOwnerId())
                    .status(currentTask.getStatus())
                    .isReady(actualIsReady)
                    .blockingCount(actualBlockingCount)
                    .openConstraintCount(actualOpenConstraintCount)
                    .estimatedDurationMin(currentTask.getEstimatedDurationMin())
                    .score(currentTask.getScore())
                    .createdAt(currentTask.getCreatedAt())
                    .updatedAt(LocalDateTime.now())
                    .deletedAt(currentTask.getDeletedAt())
                    .build()
    ).orElseThrow(() -> new TaskNotFoundException(taskId));

    log.info("Task {} reconciliation corrected persisted state", taskId);

    return true;
}

    private int calculateBlockingCount(UUID taskId) {

        List<TaskDependency> dependencies = taskDependencyRepository.findByTaskId(taskId);

        List<UUID> blockerIds = dependencies.stream()
                .map(TaskDependency::getDependsOnTaskId)
                .collect(Collectors.toList());

        if (blockerIds.isEmpty()) {
            return 0;
        }

        List<Task> blockers = taskRepository.findAllById(blockerIds);

        return (int) blockers.stream()
                .filter(this::isBlockingTask)
                .count();
    }

    private boolean isBlockingTask(Task task) {
        return task.getStatus() != TaskStatus.DONE;
    }

    private int calculateOpenConstraintCount(UUID taskId) {

        return (int) constraintRepository.findByTaskId(taskId)
                .stream()
                .filter(constraint -> constraint.getStatus() == Constraint.ConstraintStatus.PENDING)
                .count();
    }

    public void reconcileTasks(List<UUID> taskIds) {

        long startTime = System.currentTimeMillis();

        int reconciledCount = 0;
        int inconsistenciesDetected = 0;
        int correctedCount = 0;
        int failureCount = 0;

        for (UUID taskId : taskIds) {
            try {
                reconciledCount++;

                boolean changed = reconcileTaskState(taskId);

                if (changed) {
                    inconsistenciesDetected++;
                    correctedCount++;
                }

            } catch (Exception ex) {
                failureCount++;

                log.error(
                        "Failed to reconcile task {}",
                        taskId,
                        ex);
            }
        }

        long durationMs = System.currentTimeMillis() - startTime;

        log.info(
                "Batch reconciliation completed. " +
                        "tasksReconciled={}, inconsistenciesDetected={}, " +
                        "statesCorrected={}, failures={}, durationMs={}",
                reconciledCount,
                inconsistenciesDetected,
                correctedCount,
                failureCount,
                durationMs);
    }
}