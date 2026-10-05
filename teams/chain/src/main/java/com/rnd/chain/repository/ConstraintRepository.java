package com.rnd.chain.repository;

import com.rnd.chain.dto.Constraint;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Contract for persisting and looking up Constraints raised against a task.
 *
 * <p>This is a pure persistence contract - it does not enforce business rules such as valid
 * status transitions. Those live in {@code TaskEligibilityService}, which callers should use
 * instead of this repository directly whenever those rules need to be enforced.
 *
 * <p>{@link #save} and {@link #updateStatus} both report a DB-verified fact about what changed
 * (whether the row was new, and what its status was immediately before this call) rather than
 * just "did this operation succeed" - both operations are idempotent upserts/updates that
 * "succeed" identically whether or not anything actually changed, so success alone can't tell a
 * caller whether a redelivered event needs to affect {@code Task.openConstraintCount}. This lets
 * the caller derive that decision from real persisted state instead of a separate in-memory
 * idempotency ledger (see teams/chain/CLAUDE.md "Key decisions", 2026-09-22).
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
     * Upserts the given constraint by {@code constraintId}, atomically: inserts it if its id
     * doesn't already exist, or updates the existing row's mutable fields (status, reason,
     * updatedAt) if it does - so reprocessing the same constraint event never creates a
     * duplicate row.
     *
     * @return {@code true} if this {@code constraintId} did not exist before this call (a
     * genuinely new constraint); {@code false} if a row already existed and was updated instead
     * (a redelivered "constraint added" event).
     */
    boolean save(Constraint constraint);

    /**
     * Atomically transitions an existing constraint's status (and reason/updatedAt), returning
     * the status it had immediately before this call - not a caller-supplied "previous status",
     * so a redelivered event that repeats a transition already applied is detectable from real
     * state, not trusted from the caller's own claim.
     *
     * <p>{@code taskId} is part of the match, not just a check afterwards: the update only touches
     * a row whose {@code constraint_id} AND {@code task_id} both match, so a mismatched task can
     * never mutate a constraint it doesn't own.
     *
     * @return the constraint's status immediately before this update.
     * @throws IllegalStateException if no constraint with this id belongs to this task (either it
     * doesn't exist, or it belongs to a different task) - callers must go through {@link #save}
     * first for a constraint they haven't seen before.
     */
    Constraint.ConstraintStatus updateStatus(UUID taskId, String constraintId,
                                              Constraint.ConstraintStatus newStatus,
                                              Map<String, Object> reason, LocalDateTime updatedAt);
}
