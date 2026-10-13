package com.rnd.chain.repository;

import com.rnd.chain.dto.TaskDependency;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Contract for persisting and looking up TaskDependency edges (depends_on
 * / blocking relationships between tasks). NOTE: TaskDependency itself is
 * owned by a teammate, not defined here.
 *
 * <p>This is a pure persistence contract - it does not enforce business
 * rules such as duplicate-edge prevention, self-dependency rejection, or
 * validating that the referenced tasks exist. Those live in
 * {@code TaskDependencyService}, which callers should use instead of this
 * repository directly whenever those rules need to be enforced.
 */
public interface TaskDependencyRepository {

    /**
     * @return all dependency edges where the given task is the dependent
     * side (i.e. edges representing "this task depends on X"). Each
     * edge's dependsOnTaskId is a direct blocker. Empty list, never null,
     * if the task has no dependencies.
     */
    List<TaskDependency> findByTaskId(UUID taskId);

    /**
     * Same as {@link #findByTaskId(UUID)}, but may be called only from a context that already
     * holds external synchronization equivalent to this repository's own write-exclusivity
     * guarantee (e.g. {@code TaskEligibilityService}'s graph lock, which every
     * edge mutation and every cycle-detection traversal already runs under). The default
     * implementation is simply {@link #findByTaskId(UUID)} - safe to call from anywhere, at the
     * cost of this repository's normal internal locking. An implementation MAY override this to
     * skip that internal locking on this path once it's certain the caller-provided exclusivity
     * makes it redundant here (see {@code InMemoryTaskDependencyRepository} for the motivating
     * case: a cycle-detection DFS that calls this once per visited graph node, entirely inside
     * that external lock).
     *
     * <p><b>Calling this from outside that guarantee is unsafe</b> - unlike
     * {@link #findByTaskId}, it does not protect itself against a concurrent write.
     */
    default List<TaskDependency> findByTaskIdUnderExternalLock(UUID taskId) {
        return findByTaskId(taskId);
    }

    /**
     * @return all dependency edges where the given task is the blocking
     * side (i.e. edges representing "X depends on this task"). Empty
     * list, never null, if nothing depends on the task.
     */
    List<TaskDependency> findByDependsOnTaskId(UUID taskId);

    /**
     * @return true if the edge (taskId, dependsOnTaskId) currently exists. A dedicated
     * membership query (review finding #16) rather than making every caller materialize the
     * full {@link #findByTaskId} list just to test one id against it.
     */
    boolean existsDependency(UUID taskId, UUID dependsOnTaskId);

    /**
     * Persists the given edge atomically: the check for whether the edge
     * already exists and the insertion happen as a single indivisible
     * operation, so concurrent callers saving the same edge cannot both
     * observe "it didn't exist yet".
     *
     * @return {@code Optional.of(dependency)} if the edge was actually
     * created (it did not exist before this call).
     * {@code Optional.empty()} if the edge already existed and no change
     * was made (no-op).
     */
    Optional<TaskDependency> save(TaskDependency dependency);

    /**
     * Removes the edge (taskId, dependsOnTaskId) atomically: the check
     * for whether the edge exists and the removal happen as a single
     * indivisible operation.
     *
     * @return {@code true} if such an edge existed and was removed.
     * {@code false} if no such edge existed and no change was made.
     */
    boolean delete(UUID taskId, UUID dependsOnTaskId);
}
