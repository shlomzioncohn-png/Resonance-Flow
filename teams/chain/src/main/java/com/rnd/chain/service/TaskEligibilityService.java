package com.rnd.chain.service;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.dto.TaskEligibilityResponse;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.exception.TaskNotFoundException;
import com.rnd.chain.exception.TenantReassignmentException;
import com.rnd.chain.repository.GraphLock;
import com.rnd.chain.repository.TaskDependencyRepository;
import com.rnd.chain.repository.TaskRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Task status transitions and the readiness they imply; see {@link GraphLock} for the
 * locking rationale.
 *
 * <p>{@code @Transactional} on the methods below now provides real atomicity and rollback -
 * {@link #onTaskStatusChanged} holds a row lock on the changed task and every one of its
 * dependents simultaneously until it returns, so they all commit or roll back together.
 */
@Service
public class TaskEligibilityService {

    private static final Logger log =
            LoggerFactory.getLogger(TaskEligibilityService.class);

    private final TaskRepository taskRepository;
    private final TaskDependencyRepository taskDependencyRepository;
    private final SchedulingUpdateService schedulingUpdateService;
    private final GraphLock graphLock;
    private final TaskCounterUpdater taskCounterUpdater;

    public TaskEligibilityService(
            TaskRepository taskRepository,
            TaskDependencyRepository taskDependencyRepository,
            SchedulingUpdateService schedulingUpdateService,
            GraphLock graphLock,
            TaskCounterUpdater taskCounterUpdater) {

        this.taskRepository = taskRepository;
        this.taskDependencyRepository = taskDependencyRepository;
        this.schedulingUpdateService = schedulingUpdateService;
        this.graphLock = graphLock;
        this.taskCounterUpdater = taskCounterUpdater;
    }

    public void recalculateTaskEligibility(UUID taskId) {

        Objects.requireNonNull(
                taskId,
                "taskId must not be null");

        taskCounterUpdater.updateCounters(
                taskId,
                task -> TaskCounterUpdater.withCounters(
                        task,
                        task.getBlockingCount(),
                        task.getOpenConstraintCount()));
    }

    @Transactional
    public void onTaskStatusChanged(
            UUID taskId,
            TaskStatus newStatus) {

        Objects.requireNonNull(
                taskId,
                "taskId must not be null");

        Objects.requireNonNull(
                newStatus,
                "newStatus must not be null");

        graphLock.runExclusive(() -> {

            AtomicReference<Task> previous =
                    new AtomicReference<>();

            TaskCounterUpdater.requireUpdated(
                    taskRepository.update(
                            taskId,
                            task -> {
                                previous.set(task);

                                return TaskCounterUpdater.copyOf(task)
                                        .status(newStatus)
                                        .build();
                            }),
                    taskId);

            TaskStatus previousStatus =
                    previous.get().getStatus();

            if (previousStatus != TaskStatus.DONE
                    && newStatus == TaskStatus.DONE) {

                adjustDependentsBlockingCount(
                        taskId,
                        -1);

            } else if (previousStatus == TaskStatus.DONE
                    && newStatus != TaskStatus.DONE) {

                adjustDependentsBlockingCount(
                        taskId,
                        1);
            }
        });
    }

    /**
     * Same status-change effect as {@link #onTaskStatusChanged}, but for a producer (task.updated)
     * whose events carry no id and must be ordered by the {@code updatedAt} on the event itself.
     * The staleness check, status write, and {@code updatedAt} stamp all happen inside one
     * graph-lock critical section, so a redelivered or out-of-order event cannot
     * interleave between them (PR #467 review).
     *
     * @throws TaskNotFoundException if no task with {@code taskId} exists
     * @throws TenantReassignmentException if {@code tenantId} differs from the task's already-stored,
     * non-null tenant - confirmed with the Inbox team that {@code tenant_id} never legitimately
     * changes after creation, so this always signals a bug upstream. Checked before the staleness
     * short-circuit below, so a stale/duplicate redelivery carrying a wrong tenant is still flagged.
     */
    @Transactional
    public void onTaskStatusChangedIfNewer(UUID taskId, TaskStatus newStatus, LocalDateTime eventUpdatedAt,
                                            UUID tenantId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(newStatus, "newStatus must not be null");
        Objects.requireNonNull(eventUpdatedAt, "eventUpdatedAt must not be null");

        graphLock.runExclusive(() -> {
            Task current = taskCounterUpdater.requireTask(taskId);

            if (current.getTenantId() != null && !current.getTenantId().equals(tenantId)) {
                log.error("task.updated for task {} claims tenant {} but Chain has {} stored - "
                                + "tenant_id is immutable after creation, rejecting as a bug upstream",
                        taskId, tenantId, current.getTenantId());
                throw new TenantReassignmentException("Task " + taskId + " already has tenant "
                        + current.getTenantId() + ", event claims " + tenantId);
            }

            if (current.getUpdatedAt() != null && !eventUpdatedAt.isAfter(current.getUpdatedAt())) {
                log.debug("Task {} status update ignored: event updatedAt {} is not newer than stored {}",
                        taskId, eventUpdatedAt, current.getUpdatedAt());
                return;
            }

            log.info("Task {} status changed to {}", taskId, newStatus);
            TaskStatus previousStatus = current.getStatus();
            TaskCounterUpdater.requireUpdated(taskRepository.update(taskId, task ->
                    TaskCounterUpdater.copyOf(task).status(newStatus).updatedAt(eventUpdatedAt)
                            .tenantId(tenantId).build()), taskId);

            if (previousStatus != TaskStatus.DONE && newStatus == TaskStatus.DONE) {
                adjustDependentsBlockingCount(taskId, -1);
            } else if (previousStatus == TaskStatus.DONE && newStatus != TaskStatus.DONE) {
                adjustDependentsBlockingCount(taskId, 1);
            }
        });
    }

    /**
     * Applies task.completed. The Kafka record timestamp is only compared against the stored
     * updatedAt (Inbox's clock) and never written to it, so a later task.updated is not skewed.
     *
     * @throws TaskNotFoundException if no task with {@code taskId} exists
     */
    @Transactional
    public void onTaskCompleted(UUID taskId, LocalDateTime kafkaTimestamp) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(kafkaTimestamp, "kafkaTimestamp must not be null");

        graphLock.runExclusive(() -> {
            Task current = taskCounterUpdater.requireTask(taskId);
            if (current.getStatus() == TaskStatus.DONE) {
                return;
            }
            if (current.getUpdatedAt() != null && !kafkaTimestamp.isAfter(current.getUpdatedAt())) {
                log.debug("Task {} completion ignored: record timestamp {} is not newer than stored {}",
                        taskId, kafkaTimestamp, current.getUpdatedAt());
                return;
            }

            log.info("Task {} completed", taskId);
            TaskCounterUpdater.requireUpdated(taskRepository.update(taskId, task ->
                    TaskCounterUpdater.copyOf(task).status(TaskStatus.DONE).build()), taskId);
            adjustDependentsBlockingCount(taskId, -1);
        });
    }

    private void adjustDependentsBlockingCount(
            UUID taskId,
            int delta) {

        List<TaskDependency> dependents =
                taskDependencyRepository
                        .findByDependsOnTaskId(taskId);

        List<UUID> dependentIds = dependents.stream().map(TaskDependency::taskId).toList();

        Map<UUID, Boolean> previousReadiness = new HashMap<>();
        List<Task> updated = taskRepository.updateAll(dependentIds, task -> {
            previousReadiness.put(task.getId(), task.isReady());
            return TaskCounterUpdater.withCounters(
                    task,
                    Math.max(0, task.getBlockingCount() + delta),
                    task.getOpenConstraintCount());
        });

        for (Task task : updated) {
            if (previousReadiness.get(task.getId()) != task.isReady()) {
                schedulingUpdateService.publishSchedulingUpdate(task);
            }
        }
    }

    public TaskEligibilityResponse getTaskEligibility(UUID taskId) {

        Objects.requireNonNull(
                taskId,
                "taskId must not be null");

        Task task = taskCounterUpdater.requireTask(taskId);

        return new TaskEligibilityResponse(
                task.getId(),
                task.isReady(),
                task.getBlockingCount(),
                task.getOpenConstraintCount());
    }
}
