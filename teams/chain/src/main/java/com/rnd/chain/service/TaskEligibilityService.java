package com.rnd.chain.service;

import com.rnd.chain.dto.Constraint;
import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.dto.TaskEligibilityResponse;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.exception.DependencyNotFoundException;
import com.rnd.chain.exception.DuplicateDependencyException;
import com.rnd.chain.exception.InvalidDependencyException;
import com.rnd.chain.exception.TaskNotFoundException;
import com.rnd.chain.repository.ConstraintRepository;
import com.rnd.chain.repository.GraphLock;
import com.rnd.chain.repository.TaskDependencyRepository;
import com.rnd.chain.repository.TaskRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.rnd.chain.dto.TaskEligibilityResponse;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Graph-mutating methods run inside {@link GraphLock#runExclusive}: a Postgres transaction-scoped
 * advisory lock (one constant key for the whole graph), taken before any task row lock in the
 * transaction and held until commit/rollback - so the next writer sees committed state. It serializes
 * the multi-row cycle-detection DFS and the edge-write-plus-DONE-status-read decisions; single-task
 * counters are still protected by {@link TaskRepository#update}'s row lock. Known limit: one key
 * means all graph writes queue, across every project.
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
    private final ConstraintRepository constraintRepository;
    private final GraphLock graphLock;

    public TaskEligibilityService(
            GraphLock graphLock,
            TaskRepository taskRepository,
            TaskDependencyRepository taskDependencyRepository,
            SchedulingUpdateService schedulingUpdateService,
            ConstraintRepository constraintRepository) {

        this.graphLock = graphLock;
        this.taskRepository = taskRepository;
        this.taskDependencyRepository = taskDependencyRepository;
        this.schedulingUpdateService = schedulingUpdateService;
        this.constraintRepository = constraintRepository;
    }

    private Task requireTask(UUID taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));
    }

    private static <T> T requireUpdated(
            Optional<T> updated,
            UUID taskId) {

        return updated.orElseThrow(
                () -> new TaskNotFoundException(taskId));
    }

    private static Task.Builder copyOf(Task task) {
        return Task.builder()
                .id(task.getId())
                .parentTaskId(task.getParentTaskId())
                .ownerId(task.getOwnerId())
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

    private static Task withCounters(
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

    private Task updateCounters(
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

    public void recalculateTaskEligibility(UUID taskId) {

        Objects.requireNonNull(
                taskId,
                "taskId must not be null");

        updateCounters(
                taskId,
                task -> withCounters(
                        task,
                        task.getBlockingCount(),
                        task.getOpenConstraintCount()));
    }

    @Transactional
    public TaskDependency addDependency(
            UUID taskId,
            UUID dependsOnTaskId) {

        Objects.requireNonNull(
                taskId,
                "taskId must not be null");

        Objects.requireNonNull(
                dependsOnTaskId,
                "dependsOnTaskId must not be null");

        return graphLock.runExclusive(() -> {

            // Checked before the cycle check and the task lookups: a duplicate must not require
            // either task to still exist, and an existing edge cannot be introducing a new cycle.
            if (taskDependencyRepository.existsDependency(taskId, dependsOnTaskId)) {
                throw new DuplicateDependencyException(
                        "Task " + taskId + " already depends on " + dependsOnTaskId);
            }

            if (wouldCreateCycle(
                    taskId,
                    dependsOnTaskId)) {

                throw new InvalidDependencyException(
                        "Dependency would create a cycle: "
                                + taskId
                                + " -> "
                                + dependsOnTaskId);
            }

            Task task = requireTask(taskId);
            Task dependencyTask = requireTask(dependsOnTaskId);

            Optional<TaskDependency> saved =
                    taskDependencyRepository.save(
                            new TaskDependency(
                                    taskId,
                                    dependsOnTaskId));

            TaskDependency dependency = saved.orElseThrow(
                    () -> new DuplicateDependencyException(
                            "Task "
                                    + taskId
                                    + " already depends on "
                                    + dependsOnTaskId));

            boolean dependencyAlreadyDone =
                    dependencyTask.getStatus() == TaskStatus.DONE;

            adjustBlockingCount(
                    task.getId(),
                    dependencyAlreadyDone,
                    1);

            return dependency;
        });
    }

    private record PendingEdge(UUID dependsOnTaskId, boolean isNewEdge, boolean dependsOnAlreadyDone) {
    }

    /** Cycle/existence checks only - no writes. An edge that already exists is a redelivery and
     * comes back as a no-op ({@code isNewEdge == false}), not an error. */
    private PendingEdge validateEdge(UUID taskId, UUID dependsOnTaskId) {
        if (taskDependencyRepository.existsDependency(taskId, dependsOnTaskId)) {
            return new PendingEdge(dependsOnTaskId, false, false);
        }
        if (wouldCreateCycle(taskId, dependsOnTaskId)) {
            throw new InvalidDependencyException(
                    "Dependency would create a cycle: " + taskId + " -> " + dependsOnTaskId);
        }
        requireTask(taskId);
        boolean dependsOnAlreadyDone = requireTask(dependsOnTaskId).getStatus() == TaskStatus.DONE;
        return new PendingEdge(dependsOnTaskId, true, dependsOnAlreadyDone);
    }

    /** Writes one already-validated edge - no cycle/existence checks, see {@link #validateEdge}. */
    private void applyEdge(UUID taskId, PendingEdge edge) {
        if (!edge.isNewEdge()) {
            log.info("Dependency {} -> {} already existed, blockingCount left unchanged",
                    taskId, edge.dependsOnTaskId());
            return;
        }
        taskDependencyRepository.save(new TaskDependency(taskId, edge.dependsOnTaskId()));
        adjustBlockingCount(taskId, edge.dependsOnAlreadyDone(), 1);
    }

    /**
     * Same effect as calling {@link #addDependency} once per id, but validates every entry
     * against the current graph state before applying any of them, so one invalid entry (a
     * cycle, or a dependency target Chain hasn't seen yet) fails the whole batch with zero
     * partial effect (PR #467 review, on the Kafka handler that batches a task.dependencies.proposed
     * event's array through this method). Entries that already exist are no-ops, so a redelivered
     * event is safe to apply again.
     *
     * <p>Validating up front is safe because {@link #wouldCreateCycle} only traverses edges that
     * already exist before this call, and every same-batch edge starts at {@code taskId}. The whole
     * batch runs under one graph-lock acquisition, so no concurrent writer can
     * invalidate a validated entry before it is applied.
     */
    @Transactional
    public void addDependencies(UUID taskId, List<UUID> dependsOnTaskIds) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(dependsOnTaskIds, "dependsOnTaskIds must not be null");
        List<UUID> distinctIds = dependsOnTaskIds.stream()
                .peek(id -> Objects.requireNonNull(id, "dependsOnTaskIds must not contain null elements"))
                .distinct()
                .toList();
        log.info("Adding {} distinct dependency(ies) for task {}", distinctIds.size(), taskId);

        graphLock.runExclusive(() -> {
            List<PendingEdge> validated = new ArrayList<>(distinctIds.size());
            for (UUID dependsOnTaskId : distinctIds) {
                validated.add(validateEdge(taskId, dependsOnTaskId));
            }
            for (PendingEdge edge : validated) {
                applyEdge(taskId, edge);
            }
        });
    }

    @Transactional
    public void removeDependency(
            UUID taskId,
            UUID dependsOnTaskId) {

        Objects.requireNonNull(
                taskId,
                "taskId must not be null");

        Objects.requireNonNull(
                dependsOnTaskId,
                "dependsOnTaskId must not be null");

        graphLock.runExclusive(() -> {

            requireTask(taskId);

            Task dependencyTask = requireTask(dependsOnTaskId);

            boolean removed = taskDependencyRepository.delete(
                    taskId,
                    dependsOnTaskId);

            if (!removed) {
                throw new DependencyNotFoundException(
                        "No dependency from "
                                + taskId
                                + " to "
                                + dependsOnTaskId);
            }

            boolean dependencyCurrentlyDone =
                    dependencyTask.getStatus() == TaskStatus.DONE;

            adjustBlockingCount(
                    taskId,
                    dependencyCurrentlyDone,
                    -1);
        });
    }

    private void adjustBlockingCount(
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

    private boolean wouldCreateCycle(
            UUID taskId,
            UUID dependsOnTaskId) {

        Set<UUID> visited = new HashSet<>();
        Deque<UUID> toVisit = new ArrayDeque<>();

        toVisit.push(dependsOnTaskId);

        while (!toVisit.isEmpty()) {

            UUID current = toVisit.pop();

            if (current.equals(taskId)) {
                return true;
            }

            if (!visited.add(current)) {
                continue;
            }

            // Under the graph lock, so the repository's own read lock is redundant here.
            for (TaskDependency dependency :
                    taskDependencyRepository.findByTaskIdUnderExternalLock(current)) {

                toVisit.push(
                        dependency.dependsOnTaskId());
            }
        }

        return false;
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

            requireUpdated(
                    taskRepository.update(
                            taskId,
                            task -> {
                                previous.set(task);

                                return copyOf(task)
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
     */
    @Transactional
    public void onTaskStatusChangedIfNewer(UUID taskId, TaskStatus newStatus, LocalDateTime eventUpdatedAt) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(newStatus, "newStatus must not be null");
        Objects.requireNonNull(eventUpdatedAt, "eventUpdatedAt must not be null");

        graphLock.runExclusive(() -> {
            Task current = requireTask(taskId);
            if (current.getUpdatedAt() != null && !eventUpdatedAt.isAfter(current.getUpdatedAt())) {
                log.debug("Task {} status update ignored: event updatedAt {} is not newer than stored {}",
                        taskId, eventUpdatedAt, current.getUpdatedAt());
                return;
            }

            log.info("Task {} status changed to {}", taskId, newStatus);
            TaskStatus previousStatus = current.getStatus();
            requireUpdated(taskRepository.update(taskId, task ->
                    copyOf(task).status(newStatus).updatedAt(eventUpdatedAt).build()), taskId);

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
            Task current = requireTask(taskId);
            if (current.getStatus() == TaskStatus.DONE) {
                return;
            }
            if (current.getUpdatedAt() != null && !kafkaTimestamp.isAfter(current.getUpdatedAt())) {
                log.debug("Task {} completion ignored: record timestamp {} is not newer than stored {}",
                        taskId, kafkaTimestamp, current.getUpdatedAt());
                return;
            }

            log.info("Task {} completed", taskId);
            requireUpdated(taskRepository.update(taskId, task ->
                    copyOf(task).status(TaskStatus.DONE).build()), taskId);
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
            return withCounters(
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

    /**
     * Persists {@code constraint} and, if it's genuinely new, increments its task's
     * {@code openConstraintCount}.
     *
     * @throws NullPointerException if constraint is null
     * @throws TaskNotFoundException if the constraint's task doesn't exist or is soft-deleted -
     * checked before writing, so a rejected call never leaves an orphaned constraint row.
     */
    @Transactional
    public void onConstraintAdded(Constraint constraint) {
        Objects.requireNonNull(constraint, "constraint must not be null");
        UUID taskId = constraint.getTaskId();
        log.info("Constraint {} added on task {}", constraint.getConstraintId(), taskId);

        requireTask(taskId);
        boolean wasInserted = constraintRepository.save(constraint);
        if (!wasInserted) {
            log.debug("Constraint {} already existed on task {} - duplicate add, no-op",
                    constraint.getConstraintId(), taskId);
            return;
        }
        updateCounters(
                taskId,
                task -> withCounters(
                        task,
                        task.getBlockingCount(),
                        task.getOpenConstraintCount() + 1));
    }

    /**
     * Transitions {@code constraintId}'s status and, if this crosses the open/closed (PENDING)
     * boundary, adjusts {@code taskId}'s {@code openConstraintCount}. Idempotent: the previous
     * status comes from the repository (the DB itself), not a caller-supplied claim.
     *
     * <p>{@code constraintId} must belong to {@code taskId}: the repository matches on both, so a
     * mismatched pair is rejected before anything is written.
     *
     * @throws NullPointerException if any argument is null
     * @throws IllegalStateException if no constraint with this id belongs to this task
     */
    @Transactional
    public void onConstraintStatusChanged(
            UUID taskId,
            String constraintId,
            Constraint.ConstraintStatus newStatus,
            Map<String, Object> reason) {

        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(constraintId, "constraintId must not be null");
        Objects.requireNonNull(newStatus, "newStatus must not be null");

        Constraint.ConstraintStatus previousStatus =
                constraintRepository.updateStatus(taskId, constraintId, newStatus, reason, LocalDateTime.now());

        boolean wasOpen = previousStatus == Constraint.ConstraintStatus.PENDING;
        boolean isOpenNow = newStatus == Constraint.ConstraintStatus.PENDING;
        if (wasOpen == isOpenNow) {
            log.debug("Constraint {} on task {} already reflects the {} -> {} transition - "
                    + "duplicate event, no-op", constraintId, taskId, previousStatus, newStatus);
            return;
        }

        int delta = isOpenNow ? 1 : -1;
        updateCounters(
                taskId,
                task -> withCounters(
                        task,
                        task.getBlockingCount(),
                        Math.max(0, task.getOpenConstraintCount() + delta)));
    }

    public TaskEligibilityResponse getTaskEligibility(UUID taskId) {

        Objects.requireNonNull(
                taskId,
                "taskId must not be null");

        Task task = requireTask(taskId);

        return new TaskEligibilityResponse(
                task.getId(),
                task.isReady(),
                task.getBlockingCount(),
                task.getOpenConstraintCount());
    }
}
