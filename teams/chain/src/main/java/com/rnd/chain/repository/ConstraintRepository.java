package com.rnd.chain.repository;

import com.rnd.chain.dto.Constraint;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Contract for looking up and persisting Constraint records. NOTE:
 * Constraint itself is owned by a teammate, not defined here.
 */
public interface ConstraintRepository {

    /**
     * @return all constraints attached to the given task, regardless of
     * status (PENDING, SATISFIED, WAIVED) - filtering by status is the
     * caller's responsibility, same pattern as
     * TaskDependencyRepository#findByTaskId. Empty list, never null, if
     * the task has no constraints.
     */
    List<Constraint> findByTaskId(UUID taskId);

    /**
     * @return the constraint with the given id, or empty if none exists.
     * Needed for updateConstraintStatus, which mutates a single known
     * constraint rather than the whole per-task list.
     */
    Optional<Constraint> findById(String constraintId);

    /**
     * Persists the given constraint - inserts it if its id doesn't
     * already exist, or replaces the existing one if it does (upsert).
     *
     * @return the same constraint instance that was passed in, for
     * convenient chaining at the call site.
     */
    Constraint save(Constraint constraint);
}