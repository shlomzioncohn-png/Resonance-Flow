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
     * by a call site forgetting to update it. */
    private static Task withCounters(Task task, int blockingCount, int openConstraintCount) {
        return copyOf(task)
                .blockingCount(blockingCount)
                .openConstraintCount(openConstraintCount)
                .isReady(blockingCount == 0 && openConstraintCount == 0)
                .build();
    }

    @Transactional
    public void recalculateTaskEligibility(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        taskRepository.update(taskId, task ->
                        withCounters(task, task.getBlockingCount(), task.getOpenConstraintCount()))
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
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
        taskRepository.update(taskId, task ->
                        withCounters(task, Math.max(0, task.getBlockingCount() + effectiveDelta),
                                task.getOpenConstraintCount()))
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
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
            TaskStatus previousStatus = requireTask(taskId).getStatus();
            taskRepository.update(taskId, task -> copyOf(task).status(newStatus).build())
                    .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));

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

    // Constraint-related methods below still assume Constraint status transitions
    // and a caller-supplied previousStatus - no ConstraintRepository has been shared yet,
    // so these remain as designed earlier.

    @Transactional
    public void onConstraintAdded(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        log.info("Constraint added on task {}", taskId);
        taskRepository.update(taskId, task ->
                        withCounters(task, task.getBlockingCount(), task.getOpenConstraintCount() + 1))
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
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
        taskRepository.update(taskId, task ->
                        withCounters(task, task.getBlockingCount(),
                                Math.max(0, task.getOpenConstraintCount() + delta)))
                .orElseThrow(() -> new IllegalArgumentException("Task not found: " + taskId));
    }
}
