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
 * See CLAUDE.md "Open items" for why @Transactional doesn't yet cover Task/TaskDependency
 * mutations, and for the locking rationale summarized here: dependencyGraphLock guards the
 * dependency edges plus any DONE-status read that decides whether an edge currently counts
 * against blockingCount; per-task locks (see {@link #lockFor}) guard a single task's counters.
 * dependencyGraphLock is always acquired before any per-task lock, never after, so the two
 * never form a cycle.
 */
@Service
public class TaskEligibilityService {

    private static final Logger log = LoggerFactory.getLogger(TaskEligibilityService.class);

    // Fixed-size lock striping: bounded memory (unlike one lock object per task id kept
    // forever), at the cost of unrelated tasks occasionally sharing a stripe and serializing
    // against each other unnecessarily. Fine for the current scale; revisit if it's a
    // bottleneck.
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
        int stripe = (taskId.hashCode() & 0x7fffffff) % LOCK_STRIPE_COUNT;
        return taskLockStripes[stripe];
    }

    private Task requireTask(UUID taskId) {
        return taskRepository.findById(taskId)
            .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
    }

    private boolean edgeExists(UUID taskId, UUID dependsOnTaskId) {
        return taskDependencyRepository.findByTaskId(taskId).stream()
            .anyMatch(edge -> edge.getDependsOnTaskId().equals(dependsOnTaskId));
    }

    @Transactional
    public void recalculateTaskEligibility(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        synchronized (lockFor(taskId)) {
            Task task = requireTask(taskId);
            recalculateAndSave(task);
        }
    }

    private void recalculateAndSave(Task task) {
        boolean newIsReady = task.getBlockingCount() == 0 && task.getOpenConstraintCount() == 0;
        if (task.isReady() != newIsReady) {
            log.info("Task {} eligibility changed: isReady {} -> {}", task.getId(), task.isReady(), newIsReady);
            task.setReady(newIsReady);
            taskRepository.save(task);
        } else {
            log.debug("Task {} eligibility unchanged (isReady={})", task.getId(), newIsReady);
        }
    }

    @Transactional
    public void addDependency(UUID taskId, UUID dependsOnTaskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(dependsOnTaskId, "dependsOnTaskId must not be null");
        log.info("Adding dependency: task {} depends on {}", taskId, dependsOnTaskId);

        // [CRITICAL fix] The counter mutation below used to run in a second, separately
        // acquired synchronized (lockFor(taskId)) block, after this dependencyGraphLock section
        // had already been released. dependsOnAlreadyDone/isNewEdge were decided here, under
        // this lock, but applied later under a different, narrower lock - leaving a gap where a
        // concurrent removeDependency/onTaskStatusChanged on the same edge (both of which also
        // take dependencyGraphLock) could act on the real edge/status state in between, making
        // this method's captured decision stale by the time it finally ran. Nesting the per-task
        // lock inside this one instead - the same nesting order (graph lock outer, per-task lock
        // inner) already used safely by adjustDependentsBlockingCount - closes that gap: decide
        // and act now happen in one uninterrupted critical section.
        synchronized (dependencyGraphLock) {
            if (wouldCreateCycle(taskId, dependsOnTaskId)) {
                throw new IllegalStateException(
                    "Cannot add dependency: task " + taskId + " -> " + dependsOnTaskId + " would create a cycle");
            }

            // Read under the same lock that onTaskStatusChanged uses for its DONE-transition +
            // dependents fan-out, so the two can't race: whichever of "edge becomes visible" vs
            // "status flips to DONE" happens first here is guaranteed to also be what
            // onTaskStatusChanged (or this check) sees, so the edge is counted exactly once.
            boolean dependsOnAlreadyDone = requireTask(dependsOnTaskId).getStatus() == TaskStatus.DONE;

            // save() on TaskDependencyRepository is idempotent by contract, but it doesn't tell
            // us whether the edge already existed - we check that ourselves so we only bump
            // blockingCount for a genuinely new edge.
            boolean isNewEdge = !edgeExists(taskId, dependsOnTaskId);
            taskDependencyRepository.save(new TaskDependency(taskId, dependsOnTaskId));

            if (!isNewEdge) {
                log.warn("Dependency {} -> {} already existed, blockingCount left unchanged", taskId, dependsOnTaskId);
                return;
            }

            // [MINOR fix] The nested-lock-and-adjust block below used to be duplicated here and
            // in removeDependency, differing only in +1/no-clamp vs -1/Math.max-clamp - this is
            // literally where the original PR #172 bug (and the CRITICAL lock-nesting fix above)
            // lived, so a future fix applied to only one copy would silently resurrect the same
            // class of bug in the other. Extracted into adjustBlockingCount so both call the same
            // code.
            adjustBlockingCount(taskId, dependsOnAlreadyDone, 1);
        }
    }

    // A dependency on an already-completed task will never trigger a future onTaskStatusChanged
    // transition to decrement it (add) or have already been decremented by one (remove), so
    // counting it here would leave the dependent permanently stuck / double-decrement it.
    // blockingCount is left untouched in that case, but eligibility is still recalculated: e.g.
    // if this is the task's first-ever edge and it started isReady=false by default, nothing else
    // would ever recompute it.
    private void adjustBlockingCount(UUID taskId, boolean dependsOnIsDone, int delta) {
        synchronized (lockFor(taskId)) {
            Task task = requireTask(taskId);
            if (dependsOnIsDone) {
                log.info("Dependency change for task {} (delta={}) involves a dependency that is "
                    + "already DONE, blockingCount left unchanged", taskId, delta);
            } else {
                task.setBlockingCount(Math.max(0, task.getBlockingCount() + delta));
            }
            recalculateAndSave(task);
        }
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

        // [CRITICAL fix] See addDependency for the full explanation: the counter mutation used
        // to run in a second, separately acquired synchronized (lockFor(taskId)) block after
        // dependencyGraphLock had already been released, leaving a gap where a concurrent
        // addDependency/onTaskStatusChanged on the same edge could act on stale state. Nesting
        // the per-task lock inside this one closes that gap.
        synchronized (dependencyGraphLock) {
            // delete() returns void, so we check existence beforehand to know whether the
            // counter should actually change. Doing the check and the delete under the graph
            // lock prevents two concurrent removals of the same edge from both seeing
            // existed=true and double-decrementing.
            boolean existed = edgeExists(taskId, dependsOnTaskId);

            // A missing target task is treated as "not currently DONE" (i.e. safe to decrement)
            // rather than failing the removal - a dangling edge to a deleted task should still
            // be cleanable.
            boolean dependsOnCurrentlyDone = existed
                && taskRepository.findById(dependsOnTaskId)
                    .map(t -> t.getStatus() == TaskStatus.DONE)
                    .orElse(false);

            taskDependencyRepository.delete(taskId, dependsOnTaskId);

            if (!existed) {
                log.warn("No dependency {} -> {} found to remove, skipping counter update", taskId, dependsOnTaskId);
                return;
            }

            // [MINOR fix] See addDependency's adjustBlockingCount extraction: this used to be a
            // separate, near-identical copy of the same nested-lock-and-adjust block.
            adjustBlockingCount(taskId, dependsOnCurrentlyDone, -1);
        }
    }

    @Transactional
    public void onTaskStatusChanged(UUID taskId, TaskStatus newStatus) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(newStatus, "newStatus must not be null");
        log.info("Task {} status changed to {}", taskId, newStatus);

        // The whole status flip + dependents fan-out runs under the graph lock (not just
        // lockFor(taskId)): it must be serialized against addDependency/removeDependency's
        // "is the target already DONE" read, or a concurrent add/remove and a concurrent DONE
        // transition can each decide the edge is the other's responsibility to count, and it
        // never gets counted at all.
        synchronized (dependencyGraphLock) {
            Task task = requireTask(taskId);

            TaskStatus previousStatus = task.getStatus();
            task.setStatus(newStatus);
            taskRepository.save(task);

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

        List<UUID> dependentIds = dependents.stream().map(TaskDependency::getTaskId).toList();
        List<Task> dependentTasks = taskRepository.findAllById(dependentIds);

        // Each dependent is locked individually (nested inside the already-held graph lock)
        // rather than all at once - the graph is acyclic, so a dependent's lock is never
        // already held by this same call chain, and no other code path holds a per-task lock
        // while trying to acquire the graph lock, so this nesting can't deadlock.
        for (Task dependentTask : dependentTasks) {
            synchronized (lockFor(dependentTask.getId())) {
                dependentTask.setBlockingCount(Math.max(0, dependentTask.getBlockingCount() + delta));
                recalculateAndSave(dependentTask);
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
