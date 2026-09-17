package com.rnd.chain.repository;

import com.rnd.chain.dto.Constraint;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Contract for persisting and looking up Constraints raised against a task.
 *
 * <p>This is a pure persistence contract - it does not enforce business rules such as valid
 * status transitions. Those live in {@code TaskEligibilityService}, which callers should use
 * instead of this repository directly whenever those rules need to be enforced.
 */
public interface ConstraintRepository {

    /**
     * @return every constraint raised against the given task, in no particular order. Empty
     * list, never null, if the task has no constraints.
     */
    List<Constraint> findByTaskId(UUID taskId);

    /**
     * @return the constraint with this id, or empty if it doesn't exist.
     */
    Optional<Constraint> findById(String constraintId);

    /**
     * Persists the given constraint atomically: inserts it if its id doesn't already exist, or
     * updates the existing row's mutable fields (status, reason, updatedAt) if it does - upsert
     * by {@code constraintId}, so reprocessing the same constraint event never creates a
     * duplicate row.
     *
     * @return the persisted constraint.
     */
    Constraint save(Constraint constraint);
}
