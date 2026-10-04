package com.rnd.chain.service;

import com.rnd.chain.dto.Constraint;
import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.repository.ConstraintRepository;
import com.rnd.chain.repository.TaskDependencyRepository;
import com.rnd.chain.repository.TaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
 * <p><b>{@code @Transactional} on the methods below does not currently provide atomicity or
 * rollback.</b> Task/TaskDependency live in plain in-memory repositories with no JPA
 * participation - spring-boot-starter-data-jpa and the embedded H2 dependency exist solely so a
 * PlatformTransactionManager bean is available and the annotation doesn't blow up
 * DataSourceAutoConfiguration at startup (see teams/chain/pom.xml). The actual correctness
 * guarantee here comes entirely from dependencyGraphLock plus TaskRepository#update's per-id
 * atomicity. This is intentional for now - the plan is to re-add real transactional semantics
 * once persistence moves to a real JPA-backed store (e.g. Postgres), at which point this
 * annotation will start meaning what it says.
 */
@Service
public class TaskEligibilityService {

    private static final Logger log = LoggerFactory.getLogger(TaskEligibilityService.class);

    private final TaskRepository taskRepository;
    private final TaskDependencyRepository taskDependencyRepository;
    private final ConstraintRepository constraintRepository;
    private final Object dependencyGraphLock = new Object();

    public TaskEligibilityService(TaskRepository taskRepository,
                                   TaskDependencyRepository taskDependencyRepository,
                                   ConstraintRepository constraintRepository) {
        this.taskRepository = taskRepository;
        this.taskDependencyRepository = taskDependencyRepository;
        this.constraintRepository = constraintRepository;
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
            .anyMatch(edge -> edge.getDependsOnTaskId().equals(dependsOnTaskId));
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
                toVisit.push(dep.getDependsOnTaskId());
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

        for (TaskDependency dependent : dependents) {
            UUID dependentId = dependent.getTaskId();
            taskRepository.update(dependentId, task ->
                    withCounters(task, Math.max(0, task.getBlockingCount() + delta), task.getOpenConstraintCount()));
        }
    }

    // Constraint-related methods below persist through ConstraintRepository (issue #378) and
    // derive whether openConstraintCount actually needs adjusting from what Postgres itself
    // reports about the write (was this row new / what was its status before), not from a
    // caller-supplied previousStatus or an in-memory idempotency ledger - see
    // ConstraintRepository's Javadoc and teams/chain/CLAUDE.md "Key decisions" (2026-09-22).
    //
    // Write order is deliberate: ConstraintRepository first, then the in-memory Task counter.
    // If the process fails between the two, a redelivered event re-runs the (idempotent)
    // repository call, which correctly reports "already there" / the real previous status, and
    // the counter update proceeds correctly on retry. The reverse order can silently skip the
    // repository write entirely on retry, once an in-memory idempotency check says "already
    // seen" - a permanently lost Constraint row, not just a delayed counter update.

    /**
     * Persists {@code constraint} and, if it's genuinely new, increments {@code taskId}'s
     * {@code openConstraintCount}.
     *
     * @throws NullPointerException if constraint is null
     * @throws IllegalArgumentException if {@code constraint.getTaskId()} does not reference an
     * existing, non-deleted task - checked before writing, so a rejected call never leaves an
     * orphaned constraint row behind it (the DB's own FK enforces existence, but not soft-delete
     * - {@link TaskRepository#findById} already treats a soft-deleted task as not found, per its
     * own contract, so this check is what actually closes the "non-deleted" half of #378's data
     * integrity requirement).
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
        updateCounters(taskId, task ->
                withCounters(task, task.getBlockingCount(), task.getOpenConstraintCount() + 1));
    }

    /**
     * Transitions {@code constraintId}'s status and, if this actually crosses the open/closed
     * (PENDING) boundary, adjusts {@code taskId}'s {@code openConstraintCount} accordingly.
     * Idempotent: the previous status comes from {@link ConstraintRepository#updateStatus}
     * (Postgres itself), not a caller-supplied claim, so a redelivered event that repeats a
     * transition already applied is a no-op instead of double-adjusting the counter.
     *
     * <p>{@code constraintId} must belong to {@code taskId}: the repository matches on both, so
     * a mismatched pair is rejected before anything is written (see
     * {@link ConstraintRepository#updateStatus}).
     *
     * @throws NullPointerException if any argument is null
     * @throws IllegalStateException if no constraint with this id belongs to this task
     */
    @Transactional
    public void onConstraintStatusChanged(UUID taskId, String constraintId,
                                           Constraint.ConstraintStatus newStatus,
                                           Map<String, Object> reason) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(constraintId, "constraintId must not be null");
        Objects.requireNonNull(newStatus, "newStatus must not be null");
        log.info("Constraint {} on task {} changing to {}", constraintId, taskId, newStatus);

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
        updateCounters(taskId, task ->
                withCounters(task, task.getBlockingCount(),
                        Math.max(0, task.getOpenConstraintCount() + delta)));
    }
}
