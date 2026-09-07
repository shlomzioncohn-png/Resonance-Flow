// Originally copied from chain/ruthHoltzman/dependency-repository-66; notes shortened since.

package com.rnd.chain.repository;

import com.rnd.chain.dto.Task;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Contract for looking up and persisting Task nodes. Task itself is
 * owned by a teammate, not defined here.
 */
public interface TaskRepository {

    /**
     * @return the Task with this id, or empty if missing or soft-deleted.
     */
    Optional<Task> findById(UUID id);

    /**
     * @return matching Task objects; missing or soft-deleted ids are
     * simply omitted (no exception).
     */
    List<Task> findAllById(List<UUID> ids);

    /**
     * @return direct children of the given task (parentTaskId == this
     * id). Empty list, never null, if none. Soft-deleted children excluded.
     */
    List<Task> findByParentTaskId(UUID parentTaskId);

    /**
     * Upserts the task (insert if new, replace if the id exists). Stores
     * and returns an independent copy, so later mutations don't leak
     * across.
     *
     * @return an independent copy of the persisted task.
     */
    Task save(Task task);

    /**
     * Atomically reads, applies {@code mutator}, and persists the result
     * under a per-id lock - equivalent to
     * {@code findById(id).map(mutator).map(this::save)} but race-free.
     * Use this instead of a separate findById/save pair when the new
     * value depends on the current one.
     *
     * @return the updated task, or empty if no task with this id exists.
     */
    Optional<Task> update(UUID id, UnaryOperator<Task> mutator);
}
