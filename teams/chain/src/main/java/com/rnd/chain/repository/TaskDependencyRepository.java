package com.rnd.chain.repository;

import com.rnd.chain.dto.TaskDependency;

import java.util.List;
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
     * @return all dependency edges where the given task is the blocking
     * side (i.e. edges representing "X depends on this task"). Empty
     * list, never null, if nothing depends on the task.
     */
    List<TaskDependency> findByDependsOnTaskId(UUID taskId);

    /**
     * Persists the given edge. Idempotent: saving the same (taskId,
     * dependsOnTaskId) pair more than once does not create a second
     * copy of it.
     *
     * @return the same dependency instance that was passed in, for
     * convenient chaining at the call site.
     */
    TaskDependency save(TaskDependency dependency);

    /**
     * Removes the edge (taskId, dependsOnTaskId) if it exists. A no-op,
     * not an error, if no such edge is present.
     */
    void delete(UUID taskId, UUID dependsOnTaskId);
}
