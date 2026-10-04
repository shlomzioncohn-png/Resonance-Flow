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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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

    // Delegates to the repository's O(1) membership query (review finding #16) instead of
    // materializing the full findByTaskId list just to test one edge against it.
    private boolean edgeExists(UUID taskId, UUID dependsOnTaskId) {
        return taskDependencyRepository.existsDependency(taskId, dependsOnTaskId);
    }

    /** Copies every field of {@code task} into a new Builder, so callers only need to
     * override the field(s) they're actually changing before calling build(). Must copy
     * {@code isReady} too even though {@link #withCounters} always overrides it afterward - a
     * caller that builds directly off this (like {@link #onTaskStatusChanged}) without going
     * through withCounters would otherwise silently get isReady=false (Task.Builder's isReady is
     * a primitive boolean with no default, not "whatever the source task already had"). */
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

    /**
     * Recomputes and persists {@code taskId}'s {@code isReady} flag from its current
     * {@code blockingCount}/{@code openConstraintCount} (isReady = both are zero).
     *
     * @throws NullPointerException if taskId is null
     * @throws IllegalArgumentException if no task with this id exists
     */
    // TODO(JPA): becomes a real transaction boundary once Task/TaskDependency are JPA entities (see PLAN.md Phase 3).
    @Transactional
    public void recalculateTaskEligibility(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        updateCounters(taskId, task ->
                withCounters(task, task.getBlockingCount(), task.getOpenConstraintCount()));
    }

    /**
     * Adds a dependency edge: {@code taskId} depends on (is blocked by) {@code dependsOnTaskId}.
     * Increments {@code taskId}'s {@code blockingCount} and recalculates its eligibility, unless
     * {@code dependsOnTaskId} is already DONE (nothing to block on) or the edge already existed
     * (no-op).
     *
     * @throws NullPointerException if either id is null
     * @throws IllegalArgumentException if either task does not exist
     * @throws IllegalStateException if adding this edge would create a cycle
     */
    // TODO(JPA): becomes a real transaction boundary once Task/TaskDependency are JPA entities (see PLAN.md Phase 3).
    @Transactional
    public void addDependency(UUID taskId, UUID dependsOnTaskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(dependsOnTaskId, "dependsOnTaskId must not be null");
        log.info("Adding dependency: task {} depends on {}", taskId, dependsOnTaskId);

        // The whole cycle-check + edge-insert sequence runs under the graph lock: checking for
        // a cycle and then inserting the edge must be atomic, otherwise two concurrent
        // addDependency calls on different task pairs could each pass the check and together
        // create a cycle.
        synchronized (dependencyGraphLock) {
            // Checked before wouldCreateCycle (PLAN.md Finding #15): an edge that already exists
            // cannot be introducing a new cycle - it already passed this same check when first
            // inserted, and the graph is kept acyclic by this method. Skipping the O(V+E)
            // traversal on this path matters because it runs under dependencyGraphLock, and a
            // duplicate/retried addDependency call is exactly what at-least-once event delivery
            // makes common (same concern as Finding #10). Computed before either requireTask
            // call below: both wouldCreateCycle and existsDependency only read the dependency
            // graph, so a rejected cycle is reported without ever requiring dependsOnTaskId (or
            // taskId) to exist as a Task.
            boolean isNewEdge = !edgeExists(taskId, dependsOnTaskId);
            if (isNewEdge && wouldCreateCycle(taskId, dependsOnTaskId)) {
                throw new IllegalStateException(
                    "Cannot add dependency: task " + taskId + " -> " + dependsOnTaskId + " would create a cycle");
            }

            boolean dependsOnAlreadyDone = requireTask(dependsOnTaskId).getStatus() == TaskStatus.DONE;

            // taskId is only validated for a genuinely new edge - a duplicate/retried call
            // already returns below without touching taskRepository at all, matching the
            // idempotent-no-op contract the rest of this method documents.
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

    // DFS reachability (stack-based push/pop), not BFS - see CLAUDE.md "Key decisions". Kept as
    // its own traversal rather than folded into edgeExists()'s single-hop lookup: a cycle check
    // needs to walk the whole reachable set from dependsOnTaskId, a different query shape than
    // "does this one edge exist".
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
            // findByTaskIdUnderExternalLock, not findByTaskId: this whole DFS already runs
            // inside dependencyGraphLock (see callers of wouldCreateCycle), which is exclusive
            // with every write this repository ever makes - so the repository's own per-call
            // locking would be uncontended overhead here, paid once per visited node (review
            // finding). See TaskDependencyRepository#findByTaskIdUnderExternalLock's Javadoc for
            // the exclusivity contract this relies on.
            for (TaskDependency dep : taskDependencyRepository.findByTaskIdUnderExternalLock(current)) {
                toVisit.push(dep.getDependsOnTaskId());
            }
        }
        return false;
    }

    /**
     * Removes the dependency edge {@code taskId} -&gt; {@code dependsOnTaskId}, if it exists.
     * Decrements {@code taskId}'s {@code blockingCount} and recalculates its eligibility, unless
     * {@code dependsOnTaskId} was already DONE or the edge didn't exist (no-op either way).
     *
     * @throws NullPointerException if either id is null
     * @throws IllegalArgumentException if {@code taskId} does not exist. {@code dependsOnTaskId}
     * is deliberately not required to exist - a dangling edge to a deleted task is still cleanable.
     */
    // TODO(JPA): becomes a real transaction boundary once Task/TaskDependency are JPA entities (see PLAN.md Phase 3).
    @Transactional
    public void removeDependency(UUID taskId, UUID dependsOnTaskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(dependsOnTaskId, "dependsOnTaskId must not be null");
        log.info("Removing dependency: task {} no longer depends on {}", taskId, dependsOnTaskId);

        synchronized (dependencyGraphLock) {
            // delete() returns boolean, so we check existence beforehand to know whether the
            // counter should actually change. Doing the check and the delete under the graph
            // lock prevents two concurrent removals of the same edge from both seeing
            // existed=true and double-decrementing.
            boolean existed = edgeExists(taskId, dependsOnTaskId);

            // Mirrors addDependency's read, under the same lock, for the same race-closing
            // reason. A missing target task is treated as "not currently DONE" (i.e. safe to
            // decrement) rather than failing the removal - a dangling edge to a deleted task
            // should still be cleanable.
            boolean dependsOnCurrentlyDone = existed
                && taskRepository.findById(dependsOnTaskId)
                    .map(t -> t.getStatus() == TaskStatus.DONE)
                    .orElse(false);

            // Validate taskId exists BEFORE touching taskDependencyRepository, but only when
            // there's actually an edge to remove - a rejected call must never leave a real edge
            // deleted behind it. dependsOnTaskId is deliberately NOT required to exist - see the
            // dependsOnCurrentlyDone read above, which already treats a missing target as "not
            // DONE" on purpose.
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

    /**
     * Records {@code taskId}'s new status and, when the transition crosses the DONE boundary in
     * either direction, adjusts every direct dependent's {@code blockingCount} accordingly
     * (decrementing on a transition into DONE, restoring on a transition out of DONE - tasks can
     * reopen from DONE) and recalculates each affected dependent's eligibility. Idempotent: the
     * previous status is read from the stored task, not supplied by the caller, so replaying the
     * same transition twice has no further effect the second time.
     *
     * @throws NullPointerException if either argument is null
     * @throws IllegalArgumentException if no task with this id exists
     */
    // TODO(JPA): becomes a real transaction boundary once Task/TaskDependency are JPA entities (see PLAN.md Phase 3).
    @Transactional
    public void onTaskStatusChanged(UUID taskId, TaskStatus newStatus) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(newStatus, "newStatus must not be null");

        // The whole status flip + dependents fan-out runs under the graph lock: it must be
        // serialized against addDependency/removeDependency's "is the target already DONE"
        // read, or a concurrent add/remove and a concurrent DONE transition can each decide the
        // edge is the other's responsibility to count, and it never gets counted at all. This
        // does mean a DONE transition with many dependents briefly blocks unrelated
        // addDependency/removeDependency calls - a deliberate correctness-over-throughput
        // tradeoff at the current scale.
        synchronized (dependencyGraphLock) {
            Task[] previous = new Task[1];
            requireUpdated(taskRepository.update(taskId, task -> {
                previous[0] = task;
                return copyOf(task).status(newStatus).build();
            }), taskId);
            TaskStatus previousStatus = previous[0].getStatus();

            // Logged after previousStatus is known, and only at INFO when the status actually
            // changed - a redelivered/no-op transition (e.g. ACTIVE -> ACTIVE) logs at DEBUG
            // instead, mirroring updateCounters' eligibility-change logging (review Finding #6).
            // Trade-off: at the default (INFO) log level, a duplicate/redelivered event is no
            // longer visible at all - monitoring how often redelivery actually happens now
            // requires enabling DEBUG for this class.
            if (previousStatus != newStatus) {
                log.info("Task {} status changed: {} -> {}", taskId, previousStatus, newStatus);
            } else {
                log.debug("Task {} status change to {} is a no-op (already in that status)", taskId, newStatus);
            }

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

    // Constraint-related methods below still assume Constraint status transitions - no
    // ConstraintRepository has been shared yet. They track which constraintIds are currently
    // open per task (openConstraintIdsByTask, PLAN.md Finding #10), specifically so a
    // duplicate/redelivered event is a no-op instead of double-counting: every other
    // counter-mutating path in this class guards itself this way (edgeExists() for
    // addDependency/removeDependency, the task's own stored previousStatus for
    // onTaskStatusChanged) and these two methods previously had no equivalent. The
    // check-and-mutate against openIds happens inside the updateCounters/TaskRepository#update
    // mutator lambda, so it shares that call's per-taskId atomicity - no separate lock needed.
    //
    // TODO(JPA): this map is an in-memory-only idempotency ledger with no restart story - unlike
    // every other counter mutation in this class, it isn't backed by persisted state. Once
    // Task.openConstraintCount is durably persisted (PLAN.md Phase 3) while this map stays a
    // JVM-local field, a service restart will desynchronize the two: a redelivered "constraint
    // added" event for a constraint that was already open before the restart would be seen as
    // new and double-count it. Must be rebuilt from persisted state on startup (or replaced by a
    // persisted openConstraintIds column/table) before that migration ships.
    private final Map<UUID, Set<UUID>> openConstraintIdsByTask = new ConcurrentHashMap<>();

    /**
     * Records that {@code constraintId} is now open on {@code taskId}: increments
     * {@code openConstraintCount} and recalculates eligibility. Idempotent per {@code
     * constraintId} - calling this again for a constraint already tracked as open on this task is
     * a no-op, so a redelivered "constraint added" event cannot inflate the counter twice.
     *
     * @throws NullPointerException if either id is null
     * @throws IllegalArgumentException if no task with this id exists
     */
    // TODO(JPA): becomes a real transaction boundary once Task/TaskDependency are JPA entities (see PLAN.md Phase 3).
    @Transactional
    public void onConstraintAdded(UUID taskId, UUID constraintId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(constraintId, "constraintId must not be null");
        log.info("Constraint {} added on task {}", constraintId, taskId);
        updateCounters(taskId, task -> {
            Set<UUID> openIds = openConstraintIdsByTask.computeIfAbsent(taskId, id -> ConcurrentHashMap.newKeySet());
            if (!openIds.add(constraintId)) {
                log.debug("Constraint {} already tracked as open on task {} - duplicate add, no-op",
                    constraintId, taskId);
                return task;
            }
            return withCounters(task, task.getBlockingCount(), task.getOpenConstraintCount() + 1);
        });
    }

    /**
     * Records {@code constraintId}'s status transition on {@code taskId}, adjusting
     * {@code openConstraintCount} and recalculating eligibility only when this actually flips the
     * constraint between open (PENDING) and not-open (any other status) - constraints, like
     * tasks, can reopen. Idempotent per {@code constraintId}: whether the constraint is currently
     * tracked as open is read from {@link #openConstraintIdsByTask}, not solely trusted from the
     * caller-supplied {@code previousStatus}, so a redelivered event that repeats a transition
     * already applied is a no-op instead of double-adjusting the counter.
     *
     * @throws NullPointerException if any argument is null
     * @throws IllegalArgumentException if no task with this id exists
     */
    // TODO(JPA): becomes a real transaction boundary once Task/TaskDependency are JPA entities (see PLAN.md Phase 3).
    @Transactional
    public void onConstraintStatusChanged(UUID taskId,
                                           UUID constraintId,
                                           Constraint.ConstraintStatus previousStatus,
                                           Constraint.ConstraintStatus newStatus) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(constraintId, "constraintId must not be null");
        Objects.requireNonNull(previousStatus, "previousStatus must not be null");
        Objects.requireNonNull(newStatus, "newStatus must not be null");
        log.info("Constraint {} on task {} changed: {} -> {}", constraintId, taskId, previousStatus, newStatus);

        boolean wasOpen = previousStatus == Constraint.ConstraintStatus.PENDING;
        boolean isOpenNow = newStatus == Constraint.ConstraintStatus.PENDING;
        if (wasOpen == isOpenNow) {
            // Genuinely no open/close transition per the caller's own claim (e.g.
            // SATISFIED -> WAIVED) - cheap fast path, no lock/repository touch needed.
            return;
        }

        updateCounters(taskId, task -> {
            Set<UUID> openIds = openConstraintIdsByTask.computeIfAbsent(taskId, id -> ConcurrentHashMap.newKeySet());
            boolean currentlyTrackedOpen = openIds.contains(constraintId);
            if (currentlyTrackedOpen == isOpenNow) {
                // Ground truth already reflects the target state - a duplicate/redelivered event
                // repeating a transition already applied, not trusted solely on the caller's
                // previousStatus claim. No-op.
                log.debug("Constraint {} on task {} already reflects the {} -> {} transition - duplicate event, no-op",
                    constraintId, taskId, previousStatus, newStatus);
                return task;
            }
            int delta;
            if (isOpenNow) {
                openIds.add(constraintId);
                delta = 1;
            } else {
                openIds.remove(constraintId);
                // Mirrors InMemoryTaskDependencyRepository's removeIfEmpty: drop the outer map
                // entry once its constraint set is empty, so a task that once had a constraint
                // doesn't permanently occupy an entry in this map after all its constraints
                // close (review Finding #2 - unbounded growth otherwise). Safe without extra
                // locking: this taskId's entry in openConstraintIdsByTask is only ever mutated
                // from inside this taskId's own TaskRepository#update mutator, which already
                // runs with per-id exclusivity.
                openConstraintIdsByTask.computeIfPresent(taskId, (id, ids) -> ids.isEmpty() ? null : ids);
                delta = -1;
            }
            return withCounters(task, task.getBlockingCount(), Math.max(0, task.getOpenConstraintCount() + delta));
        });
    }
}
