package com.rnd.chain.repository;

import com.rnd.chain.dto.Task;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Contract for looking up and persisting Task nodes. NOTE: Task itself
 * is owned by a teammate, not defined here.
 */
public interface TaskRepository {

    /**
     * @return the Task with this id, or empty if it doesn't exist -
     * including tasks that are soft-deleted (deletedAt != null), which
     * are treated as not found.
     */
    Optional<Task> findById(UUID id);

    /**
     * @return the Task objects matching the given ids. Ids that don't
     * exist, or belong to a soft-deleted task, are simply omitted from
     * the result (no exception).
     */
    List<Task> findAllById(List<UUID> ids);

    /**
     * @return the direct children of the given task in the hierarchy
     * (tasks whose parentTaskId equals this id). Empty list, never null,
     * if the task has no children. Soft-deleted children are excluded,
     * same as the other lookups here.
     */
    List<Task> findByParentTaskId(UUID parentTaskId);

    /**
     * Persists the given task - inserts it if its id doesn't already
     * exist, or replaces the existing one if it does (upsert). Contract:
     * mutations the caller makes to its own {@code task} reference after
     * calling this method must not affect the stored task, and vice versa
     * - implementations are free to satisfy this however fits their
     * storage model (e.g. because the value type is immutable, or via an
     * explicit defensive copy).
     *
     * @return the persisted task, safe to use per the contract above.
     */
    Task save(Task task);

    /**
     * Atomically reads the current task with this id, applies
     * {@code mutator} to it, and persists the result - equivalent to
     * {@code findById(id).map(mutator).map(this::save)} but performed as a
     * single operation under a per-id lock. Use this instead of a separate
     * findById/save pair whenever the new value depends on the current one
     * (e.g. decrementing a counter), since two concurrent findById/save
     * pairs on the same id can otherwise race and silently lose one of the
     * updates. {@code mutator} must not mutate the task it's given in
     * place - it must return the new value, per the same save() contract.
     * {@code mutator} must not return null: implementations must reject a
     * null result (e.g. by throwing {@link NullPointerException}) rather
     * than treating it as "delete this task".
     *
     * @return the updated task, or empty if no task with this id exists
     */
    Optional<Task> update(UUID id, UnaryOperator<Task> mutator);
}