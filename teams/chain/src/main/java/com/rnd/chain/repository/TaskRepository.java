package com.rnd.chain.repository;

import com.rnd.chain.dto.Task;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
     * exist, or replaces the existing one if it does (upsert).
     *
     * @return the same task instance that was passed in, for convenient
     * chaining at the call site.
     */
    Task save(Task task);
}