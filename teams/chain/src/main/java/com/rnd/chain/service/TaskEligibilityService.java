package com.rnd.chain.service;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.dto.TaskEligibilityResponse;
import com.rnd.chain.dto.TaskReadinessSnapshot;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.dto.TaskTier;
import com.rnd.chain.exception.TaskNotFoundException;
import com.rnd.chain.exception.TenantReassignmentException;
import com.rnd.chain.repository.GraphLock;
import com.rnd.chain.repository.TaskDependencyRepository;
import com.rnd.chain.repository.TaskReadinessRepository;
import com.rnd.chain.repository.TaskRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Task status transitions and the readiness they imply; see {@link GraphLock} for the
 * locking rationale.
 *
 * <p>{@code @Transactional} on the methods below provides atomicity and rollback.
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class TaskEligibilityService {

    /** How far before a DONE task.updated's own updated_at a task created from it is stamped. */
    static final Duration DONE_EVENT_UPDATED_AT_SKEW = Duration.ofSeconds(1);

    private final TaskRepository taskRepository;
    private final TaskDependencyRepository taskDependencyRepository;
    private final GraphLock graphLock;
    private final DependencyEdgeService dependencyEdgeService;
    private final PriorityScoreService priorityScoreService;
    private final TaskStateHistoryRecorder historyRecorder;
    private final TaskReadinessRepository taskReadinessRepository;

    @Transactional
    public void recalculateTaskEligibility(UUID taskId) {

        Objects.requireNonNull(
                taskId,
                "taskId must not be null");

        TaskReadinessSnapshot readiness =
                requireReadinessSnapshot(
                        taskReadinessRepository.findByIds(
                                List.of(taskId)),
                        taskId);

        dependencyEdgeService.recomputePriority(
                taskId,
                readiness,
                readiness);
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
        UUID lockTenantId =
                tenantIdForTask(taskId);

        graphLock.runExclusive(  lockTenantId,() -> {

            Task current =
                    taskRepository.findById(taskId)
                    .orElseThrow(
                            () -> new TaskNotFoundException(taskId));

            TaskStatus previousStatus =
                    current.getStatus();

            boolean affectsDependents =
                    (previousStatus != TaskStatus.DONE
                            && newStatus == TaskStatus.DONE)
                            || (previousStatus == TaskStatus.DONE
                            && newStatus != TaskStatus.DONE);

            List<UUID> dependentIds =
                    List.of();

            Map<UUID, TaskReadinessSnapshot> beforeReadiness =
                    Map.of();

            /*
             * Read the View BEFORE changing the blocker status.
             * Once the blocker becomes DONE/non-DONE the View itself changes.
             */
            if (affectsDependents) {

                dependentIds =
                        taskDependencyRepository
                                .findByDependsOnTaskId(taskId)
                                .stream()
                                .map(TaskDependency::taskId)
                                .toList();

                beforeReadiness =
                        taskReadinessRepository.findByIds(
                                dependentIds);
            }

            taskRepository.update(
                            taskId,
                            task -> task.toBuilder()
                                    .status(newStatus)
                                    .build())
                    .orElseThrow(
                            () -> new TaskNotFoundException(taskId));

            historyRecorder.recordStatusChange(
                    taskId,
                    previousStatus,
                    newStatus);

            if (previousStatus != TaskStatus.DONE
                    && newStatus == TaskStatus.DONE) {

                recomputeDependents(
                        taskId,
                        dependentIds,
                        beforeReadiness);

            } else if (previousStatus == TaskStatus.DONE
                    && newStatus != TaskStatus.DONE) {

                recomputeDependents(
                        taskId,
                        dependentIds,
                        beforeReadiness);
            }
        });
    }

    /**
     * Same status-change effect as {@link #onTaskStatusChanged},
     * but for a task.updated producer whose events are ordered by
     * eventUpdatedAt.
     *
     * @throws TenantReassignmentException if tenantId differs from
     * the already stored tenant
     *
     * <p>{@code tier} is stored under the same staleness check; null keeps the stored tier.
     */
    @Transactional
    public void onTaskStatusChangedIfNewer(
            UUID taskId,
            TaskStatus newStatus,
            LocalDateTime eventUpdatedAt,
            UUID tenantId,
            TaskTier tier) {

        Objects.requireNonNull(
                taskId,
                "taskId must not be null");

        Objects.requireNonNull(
                newStatus,
                "newStatus must not be null");

        Objects.requireNonNull(
                eventUpdatedAt,
                "eventUpdatedAt must not be null");

        Objects.requireNonNull(
                tenantId,
                "tenantId must not be null");

        graphLock.runExclusive( tenantId,() -> {

            Task current =
                    taskRepository.findById(taskId)
                            .orElse(null);

            if (current == null) {

                taskRepository.insertIfAbsent(
                        newTaskFromEvent(
                                taskId,
                                tenantId,
                                newStatus,
                                eventUpdatedAt)
                                .toBuilder()
                                .tier(tier)
                                .build());

                return;
            }

            if (current.getTenantId() != null
                    && !current.getTenantId().equals(tenantId)) {

                log.error(
                        "task.updated for task {} claims tenant {} but Chain has {} stored - "
                                + "tenant_id is immutable after creation, rejecting as a bug upstream",
                        taskId,
                        tenantId,
                        current.getTenantId());

                throw new TenantReassignmentException(
                        "Task "
                                + taskId
                                + " already has tenant "
                                + current.getTenantId()
                                + ", event claims "
                                + tenantId);
            }

            if (current.getUpdatedAt() != null
                    && !eventUpdatedAt.isAfter(
                            current.getUpdatedAt())) {

                log.debug(
                        "Task {} status update ignored: event updatedAt {} is not newer than stored {}",
                        taskId,
                        eventUpdatedAt,
                        current.getUpdatedAt());

                return;
            }

            TaskStatus previousStatus =
                    current.getStatus();

            boolean affectsDependents =
                    (previousStatus != TaskStatus.DONE
                            && newStatus == TaskStatus.DONE)
                            || (previousStatus == TaskStatus.DONE
                            && newStatus != TaskStatus.DONE);

            List<UUID> dependentIds =
                    List.of();

            Map<UUID, TaskReadinessSnapshot> beforeReadiness =
                    Map.of();

            if (affectsDependents) {

                dependentIds =
                        taskDependencyRepository
                                .findByDependsOnTaskId(taskId)
                                .stream()
                                .map(TaskDependency::taskId)
                                .toList();

                beforeReadiness =
                        taskReadinessRepository.findByIds(
                                dependentIds);
            }

            log.info(
                    "Task {} status changed to {}",
                    taskId,
                    newStatus);

            taskRepository.update(
                            taskId,
                            task -> task.toBuilder()
                                    .status(newStatus)
                                    .updatedAt(eventUpdatedAt)
                                    .tenantId(tenantId)
                                    .tier(tier != null ? tier : task.getTier())
                                    .build())
                    .orElseThrow(
                            () -> new TaskNotFoundException(taskId));

            historyRecorder.recordStatusChange(
                    taskId,
                    previousStatus,
                    newStatus);

            if (previousStatus != TaskStatus.DONE
                    && newStatus == TaskStatus.DONE) {

                recomputeDependents(
                        taskId,
                        dependentIds,
                        beforeReadiness);

            } else if (previousStatus == TaskStatus.DONE
                    && newStatus != TaskStatus.DONE) {

                recomputeDependents(
                        taskId,
                        dependentIds,
                        beforeReadiness);
            }
        });
    }

    /**
     * Handles a task.updated carrying Inbox's DONE status.
     *
     * Completion itself is applied only by task.completed.
     * This method preserves task.updated's tenant validation and
     * missing-task creation behavior.
     */
    @Transactional
    public void onTaskUpdatedAsDone(
            UUID taskId,
            LocalDateTime eventUpdatedAt,
            UUID tenantId) {

        Objects.requireNonNull(
                taskId,
                "taskId must not be null");

        Objects.requireNonNull(
                eventUpdatedAt,
                "eventUpdatedAt must not be null");

        Objects.requireNonNull(
                tenantId,
                "tenantId must not be null");

        graphLock.runExclusive( tenantId,() -> {

            Task current =
                    taskRepository.findById(taskId)
                            .orElse(null);

            if (current == null) {

                taskRepository.insertIfAbsent(
                        newTaskFromEvent(
                                taskId,
                                tenantId,
                                TaskStatus.PENDING,
                                eventUpdatedAt.minus(
                                        DONE_EVENT_UPDATED_AT_SKEW)));

                return;
            }

            if (current.getTenantId() != null
                    && !current.getTenantId().equals(tenantId)) {

                throw new TenantReassignmentException(
                        "Task "
                                + taskId
                                + " already has tenant "
                                + current.getTenantId()
                                + ", event claims "
                                + tenantId);
            }

            log.debug(
                    "Task {} task.updated DONE ignored: "
                            + "completion is applied by task.completed",
                    taskId);
        });
    }

    private static Task newTaskFromEvent(
            UUID taskId,
            UUID tenantId,
            TaskStatus status,
            LocalDateTime updatedAt) {

        return Task.builder()
                .id(taskId)
                .parentTaskId(null)
                .tenantId(tenantId)
                .status(status)
                .estimatedDurationMin(null)
                .score(null)
                .createdAt(LocalDateTime.now())
                .updatedAt(updatedAt)
                .deletedAt(null)
                .build();
    }

    /**
     * Applies task.completed.
     *
     * The Kafka record timestamp is compared against the stored updatedAt
     * but is not written back to it, so a later task.updated is not skewed.
     *
     * @throws TaskNotFoundException if no task with taskId exists
     */
    @Transactional
    public void onTaskCompleted(
            UUID taskId,
            LocalDateTime kafkaTimestamp) {

        Objects.requireNonNull(
                taskId,
                "taskId must not be null");

        Objects.requireNonNull(
                kafkaTimestamp,
                "kafkaTimestamp must not be null");
        UUID lockTenantId =
               tenantIdForTask(taskId);

        graphLock.runExclusive(lockTenantId, () -> {

            Task current =
                    taskRepository.findById(taskId)
                    .orElseThrow(
                            () -> new TaskNotFoundException(taskId));

            if (current.getStatus() == TaskStatus.DONE) {
                return;
            }

            if (current.getUpdatedAt() != null
                    && !kafkaTimestamp.isAfter(
                            current.getUpdatedAt())) {

                log.debug(
                        "Task {} completion ignored: record timestamp {} is not newer than stored {}",
                        taskId,
                        kafkaTimestamp,
                        current.getUpdatedAt());

                return;
            }

            List<UUID> dependentIds =
                    taskDependencyRepository
                            .findByDependsOnTaskId(taskId)
                            .stream()
                            .map(TaskDependency::taskId)
                            .toList();

            Map<UUID, TaskReadinessSnapshot> beforeReadiness =
                    taskReadinessRepository.findByIds(
                            dependentIds);

            log.info(
                    "Task {} completed",
                    taskId);

            taskRepository.update(
                            taskId,
                            task -> task.toBuilder()
                                    .status(TaskStatus.DONE)
                                    .build())
                    .orElseThrow(
                            () -> new TaskNotFoundException(taskId));

            historyRecorder.recordStatusChange(
                    taskId,
                    current.getStatus(),
                    TaskStatus.DONE);

            recomputeDependents(
                    taskId,
                    dependentIds,
                    beforeReadiness);
        });
    }

    /**
     * Readiness/history decisions are based on task_readiness; the dependents are
     * locked and re-read so their priority can be recomputed.
     */
    private void recomputeDependents(
            UUID taskId,
            List<UUID> dependentIds,
            Map<UUID, TaskReadinessSnapshot> beforeReadiness) {

        if (dependentIds.isEmpty()) {
            return;
        }

        List<Task> locked =
                taskRepository.updateAll(
                        dependentIds,
                        UnaryOperator.identity());

        /*
         * Read again from the View AFTER the blocker/status change.
         * This is the authoritative readiness state.
         */
        Map<UUID, TaskReadinessSnapshot> afterReadiness =
                taskReadinessRepository.findByIds(
                        dependentIds);

        for (Task task : locked) {

            TaskReadinessSnapshot before =
                    requireReadinessSnapshot(
                            beforeReadiness,
                            task.getId());

            TaskReadinessSnapshot after =
                    requireReadinessSnapshot(
                            afterReadiness,
                            task.getId());

            /*
             * History receives values calculated by the View,
             * not the task table.
             */
            historyRecorder.recordBlockingTransition(
                    task.getId(),
                    before.blockingCount()
                            + before.openConstraintCount(),
                    after.blockingCount()
                            + after.openConstraintCount());

            priorityScoreService.recomputePriorityScoreAndPublish(
                    task.getId(),
                    task,
                    task,
                    before,
                    after);
        }
    }

    public TaskEligibilityResponse getTaskEligibility(
            UUID taskId) {

        Objects.requireNonNull(
                taskId,
                "taskId must not be null");

        Task task =
                taskRepository.findById(taskId)
                    .orElseThrow(
                            () -> new TaskNotFoundException(taskId));

        TaskReadinessSnapshot readiness =
                requireReadinessSnapshot(
                        taskReadinessRepository.findByIds(
                                List.of(taskId)),
                        taskId);

        return new TaskEligibilityResponse(
                task.getId(),
                readiness.isReady(),
                readiness.blockingCount(),
                readiness.openConstraintCount());
    }

    private TaskReadinessSnapshot requireReadinessSnapshot(
            Map<UUID, TaskReadinessSnapshot> snapshots,
            UUID taskId) {

        TaskReadinessSnapshot snapshot =
                snapshots.get(taskId);

        if (snapshot == null) {

            throw new IllegalStateException(
                    "No task_readiness row found for task "
                            + taskId);
        }

        return snapshot;
    }
    private UUID tenantIdForTask(
        UUID taskId) {

        return taskRepository
            .findById(taskId)
            .map(Task::getTenantId)
            .orElse(null);
        }
}