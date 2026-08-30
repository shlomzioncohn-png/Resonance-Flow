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
 * Locking rationale: dependencyGraphLock guards the dependency edges (cycle
 * check + edge insert/delete) as a whole, since a cycle can span task pairs
 * that per-task locks wouldn't serialize against each other; per-task locks
 * (see {@link #lockFor}) guard a single task's counters. dependencyGraphLock
 * is always acquired before any per-task lock, never after, so the two can't
 * deadlock.
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
        int stripe = (taskId.hashCode() & 0x7fffffff) % LOCK_STRIPE_COUNT;
        return taskLockStripes[stripe];
    }

    @Transactional
    public void recalculateTaskEligibility(UUID taskId) {
        synchronized (lockFor(taskId)) {
            Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
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
        log.info("Adding dependency: task {} depends on {}", taskId, dependsOnTaskId);

        // The whole cycle-check + edge-insert sequence runs under the graph lock: checking
        // for a cycle and then inserting the edge must be atomic, otherwise two concurrent
        // addDependency calls on different task pairs could each pass the check and together
        // create a cycle.
        synchronized (dependencyGraphLock) {
            if (wouldCreateCycle(taskId, dependsOnTaskId)) {
                throw new IllegalStateException(
                    "Cannot add dependency: task " + taskId + " -> " + dependsOnTaskId + " would create a cycle");
            }

            // save() on TaskDependencyRepository is idempotent by contract, but it doesn't
            // tell us whether the edge already existed - we check that ourselves so we
            // only bump blockingCount for a genuinely new edge.
            boolean alreadyExists = taskDependencyRepository.findByTaskId(taskId).stream()
                .anyMatch(edge -> edge.getDependsOnTaskId().equals(dependsOnTaskId));

            taskDependencyRepository.save(new TaskDependency(taskId, dependsOnTaskId));

            if (alreadyExists) {
                log.warn("Dependency {} -> {} already existed, blockingCount left unchanged", taskId, dependsOnTaskId);
                return;
            }

            // Nested inside dependencyGraphLock (graph lock always acquired before a per-task
            // lock, never after) so this counter mutation is mutually exclusive with every
            // other path that touches this task's counters (onTaskStatusChanged,
            // onConstraintAdded/StatusChanged, removeDependency), not just with other
            // addDependency/removeDependency calls.
            synchronized (lockFor(taskId)) {
                Task task = taskRepository.findById(taskId)
                    .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
                task.setBlockingCount(task.getBlockingCount() + 1);
                recalculateAndSave(task);
            }
        }
    }

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
        log.info("Removing dependency: task {} no longer depends on {}", taskId, dependsOnTaskId);

        synchronized (dependencyGraphLock) {
            // delete() returns void, so we check existence beforehand to know
            // whether the counter should actually change. Doing the check and the
            // delete under the graph lock prevents two concurrent removals of the
            // same edge from both seeing existed=true and double-decrementing.
            boolean existed = taskDependencyRepository.findByTaskId(taskId).stream()
                .anyMatch(edge -> edge.getDependsOnTaskId().equals(dependsOnTaskId));

            taskDependencyRepository.delete(taskId, dependsOnTaskId);

            if (!existed) {
                log.warn("No dependency {} -> {} found to remove, skipping counter update", taskId, dependsOnTaskId);
                return;
            }

            // See addDependency for why this is nested inside dependencyGraphLock rather than
            // taken standalone.
            synchronized (lockFor(taskId)) {
                Task task = taskRepository.findById(taskId)
                    .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
                task.setBlockingCount(Math.max(0, task.getBlockingCount() - 1));
                recalculateAndSave(task);
            }
        }
    }

    @Transactional
    public void onTaskStatusChanged(UUID taskId, TaskStatus newStatus) {
        log.info("Task {} status changed to {}", taskId, newStatus);

        synchronized (lockFor(taskId)) {
            Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));

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

        // Each dependent's Task is fetched *inside* its own lock, one at a time - not
        // batch-fetched up front. Repositories now return defensive copies (Task.copyOf), so a
        // copy fetched before the lock is acquired can already be stale by the time it's
        // mutated and saved here, silently losing a concurrent update to the same task made by
        // another onTaskStatusChanged call (e.g. two blockers of the same dependent completing
        // at once). Fetching under the lock - the same pattern every other method in this class
        // uses - makes the read-modify-write atomic again.
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
        log.info("Constraint added on task {}", taskId);
        synchronized (lockFor(taskId)) {
            Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
            task.setOpenConstraintCount(task.getOpenConstraintCount() + 1);
            recalculateAndSave(task);
        }
    }

    @Transactional
    public void onConstraintStatusChanged(UUID taskId,
                                           Constraint.ConstraintStatus previousStatus,
                                           Constraint.ConstraintStatus newStatus) {
        log.info("Constraint on task {} changed: {} -> {}", taskId, previousStatus, newStatus);

        boolean wasOpen = previousStatus == Constraint.ConstraintStatus.PENDING;
        boolean isOpenNow = newStatus == Constraint.ConstraintStatus.PENDING;
        if (wasOpen == isOpenNow) {
            return;
        }

        synchronized (lockFor(taskId)) {
            Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
            int delta = isOpenNow ? 1 : -1;
            task.setOpenConstraintCount(Math.max(0, task.getOpenConstraintCount() + delta));
            recalculateAndSave(task);
        }
    }
}
