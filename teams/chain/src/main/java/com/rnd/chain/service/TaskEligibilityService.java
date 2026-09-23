package com.rnd.chain.service;

import com.rnd.chain.dto.Constraint;
import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.repository.TaskDependencyRepository;
import com.rnd.chain.repository.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * See CLAUDE.md "Open items" for the locking rationale: dependencyGraphLock guards the
 * dependency edges plus any DONE-status read that decides whether an edge currently counts
 * against blockingCount. Per-task field mutations (blockingCount, openConstraintCount, status,
 * isReady) no longer need a hand-rolled per-task lock - {@link TaskRepository#update} already
 * performs an atomic per-id read-modify-write, so a single Task's fields can never be lost to a
 * race the way a separate findById/mutate/save pair could. dependencyGraphLock is still required
 * for decisions that span more than one Task/edge at once (cycle detection, and reading a
 * dependency's DONE status together with creating/removing the edge to it).
 *
 * <p>{@code @Transactional} on the methods below now provides real atomicity and rollback -
 * {@link #onTaskStatusChanged} holds a row lock on the changed task and every one of its
 * dependents simultaneously until it returns, so they all commit or roll back together.
 */
@Service
public class TaskEligibilityService {

    private static final Logger log = LoggerFactory.getLogger(TaskEligibilityService.class);

    private final TaskRepository taskRepository;
    private final TaskDependencyRepository taskDependencyRepository;
    private final Object dependencyGraphLock = new Object();

    public TaskEligibilityService(TaskRepository taskRepository,
                                   TaskDependencyRepository taskDependencyRepository) {
        this.taskRepository = taskRepository;
        this.taskDependencyRepository = taskDependencyRepository;
    }

    private Task requireTask(UUID taskId) {
        return taskRepository.findById(taskId)
            .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
    }

    private static <T> T requireUpdated(Optional<T> updated, UUID taskId) {
        return updated.orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
    }

    private boolean edgeExists(UUID taskId, UUID dependsOnTaskId) {
        return taskDependencyRepository.findByTaskId(taskId).stream()
            .anyMatch(edge -> edge.dependsOnTaskId().equals(dependsOnTaskId));
    }

    /** Copies every field of {@code task} into a new Builder, so callers only need to
     * override the field(s) they're actually changing before calling build(). */
    private static Task.Builder copyOf(Task task) {
        return Task.builder()
                .id(task.getId())
                .parentTaskId(task.getParentTaskId())
                .ownerId(task.getOwnerId())
                .status(task.getStatus())
                .blockingCount(task.getBlockingCount())
                .openConstraintCount(task.getOpenConstraintCount())
                .estimatedDurationMin(task.getEstimatedDurationMin())
                .score(task.getScore())
                .createdAt(task.getCreatedAt())
                .updatedAt(task.getUpdatedAt())
                .deletedAt(task.getDeletedAt());
    }

    /** The single place isReady gets recomputed from blockingCount/openConstraintCount -
     * every mutator that touches either counter goes through this, so isReady can't drift
     * by a call site forgetting to update it. Returns {@code task} itself, unchanged, when the
     * computed values are already exactly what's stored - restores the no-op-write optimization
     * the pre-refactor code had. Done here, against the live value {@link TaskRepository#update}
     * hands the mutator, rather than via a separate pre-read that could race a concurrent change
     * on the same task. Matters once persistence is a real JPA store: an unchanged returned
     * instance is exactly what makes Hibernate's own dirty checking skip the UPDATE/version bump. */
    private static Task withCounters(Task task, int blockingCount, int openConstraintCount) {
        boolean isReady = blockingCount == 0 && openConstraintCount == 0;
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

    /** Runs {@code countersMutator} through the atomic {@link TaskRepository#update}, then logs
     * whether it actually changed anything - {@code countersMutator} is expected to be a
     * {@link #withCounters} call, which signals "no-op" by returning its input unchanged. */
    private void updateCounters(UUID taskId, UnaryOperator<Task> countersMutator) {
        boolean[] changed = {false};
        Task updated = requireUpdated(taskRepository.update(taskId, task -> {
            Task result = countersMutator.apply(task);
            changed[0] = result != task;
            return result;
        }), taskId);

        if (changed[0]) {
            log.info("Task {} eligibility changed: blockingCount={}, openConstraintCount={}, isReady={}",
                    taskId, updated.getBlockingCount(), updated.getOpenConstraintCount(), updated.isReady());
        } else {
            log.debug("Task {} eligibility already correct (blockingCount={}, openConstraintCount={}, isReady={}), "
                    + "skipping write", taskId, updated.getBlockingCount(), updated.getOpenConstraintCount(),
                    updated.isReady());
        }
    }

    @Transactional
    public void recalculateTaskEligibility(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        updateCounters(taskId, task ->
                withCounters(task, task.getBlockingCount(), task.getOpenConstraintCount()));
    }

    @Transactional
    public void addDependency(UUID taskId, UUID dependsOnTaskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(dependsOnTaskId, "dependsOnTaskId must not be null");
        log.info("Adding dependency: task {} depends on {}", taskId, dependsOnTaskId);

        synchronized (dependencyGraphLock) {
            if (wouldCreateCycle(taskId, dependsOnTaskId)) {
                throw new IllegalStateException(
                    "Cannot add dependency: task " + taskId + " -> " + dependsOnTaskId + " would create a cycle");
            }

            boolean dependsOnAlreadyDone = requireTask(dependsOnTaskId).getStatus() == TaskStatus.DONE;
            boolean isNewEdge = !edgeExists(taskId, dependsOnTaskId);

            if (isNewEdge) {
                requireTask(taskId);
            }
            taskDependencyRepository.save(new TaskDependency(taskId, dependsOnTaskId));

            if (!isNewEdge) {
                log.warn("Dependency {} -> {} already existed, blockingCount left unchanged", taskId, dependsOnTaskId);
                return;
            }

            adjustBlockingCount(taskId, dependsOnAlreadyDone, 1);
        }
    }

    private void adjustBlockingCount(UUID taskId, boolean dependsOnIsDone, int delta) {
        int effectiveDelta = dependsOnIsDone ? 0 : delta;
        if (dependsOnIsDone) {
            log.info("Dependency change for task {} (delta={}) involves a dependency that is "
                + "already DONE, blockingCount left unchanged", taskId, delta);
        }
        updateCounters(taskId, task ->
                withCounters(task, Math.max(0, task.getBlockingCount() + effectiveDelta),
                        task.getOpenConstraintCount()));
    }

    // DFS reachability (stack-based push/pop), not BFS - see CLAUDE.md "Key decisions".
    private boolean wouldCreateCycle(UUID taskId, UUID dependsOnTaskId) {
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
            for (TaskDependency dep : taskDependencyRepository.findByTaskId(current)) {
                toVisit.push(dep.dependsOnTaskId());
            }
        }
        return false;
    }

    @Transactional
    public void removeDependency(UUID taskId, UUID dependsOnTaskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(dependsOnTaskId, "dependsOnTaskId must not be null");
        log.info("Removing dependency: task {} no longer depends on {}", taskId, dependsOnTaskId);

        synchronized (dependencyGraphLock) {
            boolean existed = edgeExists(taskId, dependsOnTaskId);

            boolean dependsOnCurrentlyDone = existed
                && taskRepository.findById(dependsOnTaskId)
                    .map(t -> t.getStatus() == TaskStatus.DONE)
                    .orElse(false);

            if (existed) {
                requireTask(taskId);
            }
            taskDependencyRepository.delete(taskId, dependsOnTaskId);

            if (!existed) {
                log.warn("No dependency {} -> {} found to remove, skipping counter update", taskId, dependsOnTaskId);
                return;
            }

            adjustBlockingCount(taskId, dependsOnCurrentlyDone, -1);
        }
    }

    @Transactional
    public void onTaskStatusChanged(UUID taskId, TaskStatus newStatus) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(newStatus, "newStatus must not be null");
        log.info("Task {} status changed to {}", taskId, newStatus);

        synchronized (dependencyGraphLock) {
            Task[] previous = new Task[1];
            requireUpdated(taskRepository.update(taskId, task -> {
                previous[0] = task;
                return copyOf(task).status(newStatus).build();
            }), taskId);
            TaskStatus previousStatus = previous[0].getStatus();

            if (previousStatus != TaskStatus.DONE && newStatus == TaskStatus.DONE) {
                adjustDependentsBlockingCount(taskId, -1);
            } else if (previousStatus == TaskStatus.DONE && newStatus != TaskStatus.DONE) {
                adjustDependentsBlockingCount(taskId, +1);
            }
        }
    }

    private void adjustDependentsBlockingCount(UUID taskId, int delta) {
        List<TaskDependency> dependents = taskDependencyRepository.findByDependsOnTaskId(taskId);
        log.info("Task {} status change affects {} dependent task(s), delta={}", taskId, dependents.size(), delta);

        List<UUID> dependentIds = dependents.stream().map(TaskDependency::taskId).toList();
        taskRepository.updateAll(dependentIds, task ->
                withCounters(task, Math.max(0, task.getBlockingCount() + delta), task.getOpenConstraintCount()));
    }

    // Constraint-related methods below still assume Constraint status transitions
    // and a caller-supplied previousStatus - no ConstraintRepository has been shared yet,
    // so these remain as designed earlier.

    @Transactional
    public void onConstraintAdded(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        log.info("Constraint added on task {}", taskId);
        updateCounters(taskId, task ->
                withCounters(task, task.getBlockingCount(), task.getOpenConstraintCount() + 1));
    }

    @Transactional
    public void onConstraintStatusChanged(UUID taskId,
                                           Constraint.ConstraintStatus previousStatus,
                                           Constraint.ConstraintStatus newStatus) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(previousStatus, "previousStatus must not be null");
        Objects.requireNonNull(newStatus, "newStatus must not be null");
        log.info("Constraint on task {} changed: {} -> {}", taskId, previousStatus, newStatus);

        boolean wasOpen = previousStatus == Constraint.ConstraintStatus.PENDING;
        boolean isOpenNow = newStatus == Constraint.ConstraintStatus.PENDING;
        if (wasOpen == isOpenNow) {
            return;
        }

        int delta = isOpenNow ? 1 : -1;
        updateCounters(taskId, task ->
                withCounters(task, task.getBlockingCount(),
                        Math.max(0, task.getOpenConstraintCount() + delta)));
    }
}
