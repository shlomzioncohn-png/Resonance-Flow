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
import java.util.Set;
import java.util.UUID;

/**
 * Task and TaskDependency are plain DTOs (not JPA entities yet), and the
 * repositories are in-memory - see CLAUDE.md "Open items". @Transactional is
 * kept here so the transaction boundary is already in place for when a real
 * DB lands (no service-level changes needed then); until real JPA entities
 * back Task/TaskDependency, it doesn't actually cover their mutations. The
 * in-process locking below is what makes read-modify-write on a task's
 * counters atomic today, and is correct as long as a single JVM owns the
 * in-memory repositories. Once real persistence lands, add @Version /
 * an atomic UPDATE for the counters and this locking can be retired.
 *
 * A real database has now been confirmed for Chain (see CLAUDE.md and
 * PLAN.md's "Decisions Required" - D1 is resolved). @Transactional and the
 * placeholder JPA/H2 dependencies stay as-is deliberately until that
 * migration happens - see PLAN.md's Phase 3 for the concrete recommended
 * approach (real JPA entities, atomic counter UPDATEs, retiring the locking
 * below in favor of DB-level concurrency control).
 *
 * Locking rationale: dependencyGraphLock guards the dependency edges plus any DONE-status read
 * that decides whether an edge currently counts against blockingCount (see addDependency,
 * removeDependency, onTaskStatusChanged); per-task locks (see {@link #lockFor}) guard a single
 * task's counters. dependencyGraphLock is always acquired before any per-task lock, never after,
 * so the two can't deadlock.
 */
@Service
public class TaskEligibilityService {

    private static final Logger log = LoggerFactory.getLogger(TaskEligibilityService.class);

    // Fixed-size lock striping: bounded memory (unlike one lock object per task id kept
    // forever in a growing map), at the cost of unrelated tasks occasionally sharing a
    // stripe and serializing against each other unnecessarily. Fine for the current scale;
    // revisit if it's a bottleneck.
    private static final int LOCK_STRIPE_COUNT = 256;

    private final TaskRepository taskRepository;
    private final TaskDependencyRepository taskDependencyRepository;
    private final Object[] taskLockStripes;
    private final Object dependencyGraphLock = new Object();

    public TaskEligibilityService(TaskRepository taskRepository,
                                   TaskDependencyRepository taskDependencyRepository) {
        this.taskRepository = taskRepository;
        this.taskDependencyRepository = taskDependencyRepository;
        this.taskLockStripes = new Object[LOCK_STRIPE_COUNT];
        for (int i = 0; i < LOCK_STRIPE_COUNT; i++) {
            taskLockStripes[i] = new Object();
        }
    }

    private Object lockFor(UUID taskId) {
        // Math.floorMod instead of hand-rolling a sign-bit mask (review finding #22) - the JDK
        // built-in for "always non-negative modulo" expresses the intent directly, and doesn't
        // rely on the reader recognizing the bit trick or on LOCK_STRIPE_COUNT happening to be
        // a power of two (the old mask-based version was only equivalent to this for a
        // power-of-two stripe count).
        int stripe = Math.floorMod(taskId.hashCode(), LOCK_STRIPE_COUNT);
        return taskLockStripes[stripe];
    }

    private Task requireTask(UUID taskId) {
        return taskRepository.findById(taskId)
            .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
    }

    // Delegates to the repository's O(1) membership query (review finding #16) instead of
    // materializing the full findByTaskId list just to test one edge against it.
    private boolean edgeExists(UUID taskId, UUID dependsOnTaskId) {
        return taskDependencyRepository.existsDependency(taskId, dependsOnTaskId);
    }

    @Transactional
    public void recalculateTaskEligibility(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        synchronized (lockFor(taskId)) {
            Task task = requireTask(taskId);
            recalculateAndSave(task);
        }
    }

    // Always saves, regardless of whether isReady flips: a counter can change (e.g.
    // blockingCount 2 -> 1) without crossing the ready threshold, and that change must still
    // be persisted. In-memory repos mask this today because save() stores the same reference
    // that was already mutated in place, but a real persistence layer would silently drop it.
    private void recalculateAndSave(Task task) {
        boolean previousIsReady = task.isReady();
        boolean newIsReady = task.getBlockingCount() == 0 && task.getOpenConstraintCount() == 0;
        task.setReady(newIsReady);
        taskRepository.save(task);
        if (previousIsReady != newIsReady) {
            log.info("Task {} eligibility changed: isReady {} -> {}", task.getId(), previousIsReady, newIsReady);
        } else {
            log.debug("Task {} eligibility unchanged (isReady={})", task.getId(), newIsReady);
        }
    }

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
            // Validate both tasks exist BEFORE touching taskDependencyRepository at all. A
            // rejected call (missing task, cycle) must never leave a persisted edge behind -
            // previously requireTask(dependsOnTaskId)/requireTask(taskId) only ran after
            // save(), so a caller passing a nonexistent id could get an exception while a
            // phantom edge was permanently stored. See PLAN.md Finding #1.
            requireTask(taskId);
            Task dependsOnTask = requireTask(dependsOnTaskId);

            if (wouldCreateCycle(taskId, dependsOnTaskId)) {
                throw new IllegalStateException(
                    "Cannot add dependency: task " + taskId + " -> " + dependsOnTaskId + " would create a cycle");
            }

            // edgeExists() doesn't tell us whether the edge already existed before save() -
            // check first, since save() on TaskDependencyRepository is idempotent by contract.
            boolean isNewEdge = !edgeExists(taskId, dependsOnTaskId);
            taskDependencyRepository.save(new TaskDependency(taskId, dependsOnTaskId));

            if (!isNewEdge) {
                log.warn("Dependency {} -> {} already existed, blockingCount left unchanged", taskId, dependsOnTaskId);
                return;
            }

            // Reuses the dependsOnTask instance just validated above (still under
            // dependencyGraphLock, so its DONE status here is exactly what
            // onTaskStatusChanged's fan-out would also see) instead of a second
            // requireTask(dependsOnTaskId) call - closes the DONE-status race the same way as
            // before, one repository read cheaper. Only checked for a genuinely new edge - a
            // duplicate already returned above without touching taskRepository at all.
            boolean dependsOnAlreadyDone = dependsOnTask.getStatus() == TaskStatus.DONE;

            // Nested inside dependencyGraphLock (graph lock always acquired before a per-task
            // lock, never after) so this counter mutation is mutually exclusive with every
            // other path that touches this task's counters (onTaskStatusChanged,
            // onConstraintAdded/StatusChanged, removeDependency). requireTask(taskId) is called
            // again here, deliberately - the validation call above ran before this lock was
            // held, so a concurrent onConstraintAdded/onConstraintStatusChanged on this same
            // taskId (which only takes lockFor(taskId), not dependencyGraphLock) could have
            // mutated and saved a newer copy in between; reusing the earlier instance here
            // would silently clobber that update when recalculateAndSave below calls save().
            synchronized (lockFor(taskId)) {
                Task task = requireTask(taskId);
                if (dependsOnAlreadyDone) {
                    // A dependency on an already-completed task will never trigger a future
                    // onTaskStatusChanged transition to decrement it, so counting it here would
                    // leave the dependent permanently stuck. blockingCount is left untouched,
                    // but eligibility is still recalculated: if this is the task's first-ever
                    // edge and it started isReady=false by default, nothing else would ever
                    // recompute it.
                    log.info("Dependency {} -> {} added but {} is already DONE, blockingCount left unchanged",
                        taskId, dependsOnTaskId, dependsOnTaskId);
                } else {
                    task.setBlockingCount(task.getBlockingCount() + 1);
                }
                recalculateAndSave(task);
            }
        }
    }

    // DFS reachability (stack-based push/pop). Kept as its own traversal rather than folded
    // into edgeExists()'s single-hop lookup: a cycle check needs to walk the whole reachable
    // set from dependsOnTaskId, which is a different query shape than "does this one edge
    // exist" - consolidating them would mean either running the full traversal on every
    // edge-existence check, or complicating this method to also special-case a single hop.
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
            // Validate taskId exists BEFORE touching taskDependencyRepository. A rejected call
            // must never leave a real edge deleted behind it. dependsOnTaskId is deliberately
            // NOT required to exist here - see the dependsOnCurrentlyDone read below, which
            // already treats a missing target as "not DONE" on purpose: a dangling edge to a
            // deleted task should still be cleanable. See PLAN.md Finding #1.
            requireTask(taskId);

            // delete() returns void, so we check existence beforehand to know whether the
            // counter should actually change. Doing the check and the delete under the graph
            // lock prevents two concurrent removals of the same edge from both seeing
            // existed=true and double-decrementing.
            boolean existed = edgeExists(taskId, dependsOnTaskId);
            taskDependencyRepository.delete(taskId, dependsOnTaskId);

            if (!existed) {
                log.warn("No dependency {} -> {} found to remove, skipping counter update", taskId, dependsOnTaskId);
                return;
            }

            // Mirrors addDependency's read, under the same lock, for the same race-closing
            // reason. A missing target task is treated as "not currently DONE" (i.e. safe to
            // decrement) rather than failing the removal - a dangling edge to a deleted task
            // should still be cleanable.
            boolean dependsOnCurrentlyDone = taskRepository.findById(dependsOnTaskId)
                .map(t -> t.getStatus() == TaskStatus.DONE)
                .orElse(false);

            // requireTask(taskId) is called again here, deliberately, for the same
            // staleness-safety reason documented in addDependency: the validation call above
            // ran before lockFor(taskId) was held, so a concurrent counter-mutating call could
            // have saved a newer copy in between.
            synchronized (lockFor(taskId)) {
                Task task = requireTask(taskId);
                if (dependsOnCurrentlyDone) {
                    // Mirrors addDependency: an edge to an already-DONE task never counted
                    // against blockingCount in the first place (or was already subtracted when
                    // it transitioned), so removing it must not decrement again - but still
                    // recalculate for the same defensive reason as addDependency.
                    log.info("Dependency {} -> {} removed but {} was already DONE, blockingCount left unchanged",
                        taskId, dependsOnTaskId, dependsOnTaskId);
                } else {
                    task.setBlockingCount(Math.max(0, task.getBlockingCount() - 1));
                }
                recalculateAndSave(task);
            }
        }
    }

    @Transactional
    public void onTaskStatusChanged(UUID taskId, TaskStatus newStatus) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(newStatus, "newStatus must not be null");
        log.info("Task {} status changed to {}", taskId, newStatus);

        // The whole status flip + dependents fan-out runs under the graph lock (not just
        // lockFor(taskId)): it must be serialized against addDependency/removeDependency's "is
        // the target already DONE" read, or a concurrent add/remove and a concurrent DONE
        // transition can each decide the edge is the other's responsibility to count, and it
        // never gets counted at all. This does mean a DONE transition with many dependents
        // briefly blocks unrelated addDependency/removeDependency calls - a deliberate
        // correctness-over-throughput tradeoff at the current scale.
        synchronized (dependencyGraphLock) {
            // Also nested under lockFor(taskId) (graph lock always acquired before a per-task
            // lock, never after - same discipline as addDependency/removeDependency): this
            // status read-modify-write was previously guarded ONLY by dependencyGraphLock, which
            // gave it no mutual exclusion against onConstraintAdded/onConstraintStatusChanged -
            // both of which mutate this same Task's counters under lockFor(taskId) alone. Two
            // different monitors protecting writes to the same Task record is a lost-update race:
            // whichever save() ran last would silently clobber the other's change. See PLAN.md /
            // review Finding #1.
            TaskStatus previousStatus;
            synchronized (lockFor(taskId)) {
                Task task = requireTask(taskId);
                previousStatus = task.getStatus();
                task.setStatus(newStatus);
                taskRepository.save(task);
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

        if (dependents.isEmpty()) {
            return;
        }

        // Each dependent's Task is fetched *inside* its own lock, one at a time - not
        // batch-fetched up front. Repositories return defensive copies (Task.copyOf), so a
        // copy fetched before the lock is acquired can already be stale by the time it's
        // mutated and saved here, silently losing a concurrent update to the same task made by
        // another caller. Fetching under the lock - the same pattern every other method in this
        // class uses - makes the read-modify-write atomic.
        //
        // Locked individually rather than under the caller's lock (held on taskId, never on a
        // dependent - the graph is acyclic, so this ordering is consistent across calls and
        // can't deadlock).
        for (TaskDependency dependent : dependents) {
            UUID dependentId = dependent.getTaskId();
            synchronized (lockFor(dependentId)) {
                taskRepository.findById(dependentId).ifPresent(dependentTask -> {
                    dependentTask.setBlockingCount(Math.max(0, dependentTask.getBlockingCount() + delta));
                    recalculateAndSave(dependentTask);
                });
            }
        }
    }

    // Constraint-related methods below still assume Constraint status transitions
    // and a caller-supplied previousStatus - no ConstraintRepository has been shared yet,
    // so these remain as designed earlier.

    @Transactional
    public void onConstraintAdded(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        log.info("Constraint added on task {}", taskId);
        synchronized (lockFor(taskId)) {
            Task task = requireTask(taskId);
            task.setOpenConstraintCount(task.getOpenConstraintCount() + 1);
            recalculateAndSave(task);
        }
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

        synchronized (lockFor(taskId)) {
            Task task = requireTask(taskId);
            int delta = isOpenNow ? 1 : -1;
            task.setOpenConstraintCount(Math.max(0, task.getOpenConstraintCount() + delta));
            recalculateAndSave(task);
        }
    }
}
