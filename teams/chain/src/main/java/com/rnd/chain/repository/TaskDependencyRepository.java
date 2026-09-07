// Originally copied from chain/ruthHoltzman/dependency-repository-66; notes shortened since.

package com.rnd.chain.repository;

import com.rnd.chain.dto.TaskDependency;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Contract for persisting and looking up TaskDependency edges (depends_on
 * / blocking relationships between tasks). TaskDependency itself is owned
 * by a teammate, not defined here.
 *
 * <p>Pure persistence contract - no business rules (duplicate-edge
 * prevention, self-dependency rejection, existence checks). Those live in
 * {@code TaskDependencyService}.
 */
public interface TaskDependencyRepository {

    /**
     * @return edges where this task is the dependent side (this task
     * depends on X). Empty list, never null, if none.
     */
    List<TaskDependency> findByTaskId(UUID taskId);

    /**
     * @return edges where this task is the blocking side (X depends on
     * this task). Empty list, never null, if none.
     */
    List<TaskDependency> findByDependsOnTaskId(UUID taskId);

    /**
     * Atomically creates the edge if it doesn't already exist.
     *
     * @return the dependency if created, empty if it already existed (no-op).
     */
    Optional<TaskDependency> save(TaskDependency dependency);

    /**
     * Atomically removes the edge (taskId, dependsOnTaskId).
     *
     * @return true if it existed and was removed, false if it didn't exist.
     */
    boolean delete(UUID taskId, UUID dependsOnTaskId);
}
