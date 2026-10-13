// Copied verbatim from chain/ruthHoltzman/dependency-repository-66, unmodified.

package com.rnd.chain.dto;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;

// Identifier type: UUID, pending Inbox publishing its own task id type.
//
// @Getter/@Setter (PLAN.md Finding #17) generate the trivial accessors (no setter for the final
// id/createdAt fields - Lombok skips those automatically). @EqualsAndHashCode(onlyExplicitlyIncluded
// = true) + @Include on id only: this MUST stay id-only equality (the whole per-task locking
// design in TaskEligibilityService leans on it), not the all-fields default @Data would generate.
// @Builder is placed on the constructor (not the class) so its generated builder still calls this
// constructor - and therefore still runs its validation - on every build(), the same as the
// hand-written Builder it replaces. builderClassName = "Builder" keeps Task.builder()/Task.Builder
// exactly as every existing caller (e.g. TaskTestFixtures) already uses them.
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public final class Task {

    @EqualsAndHashCode.Include
    private final UUID id;
    private final UUID parentTaskId;
    private final String ownerId;

    private final TaskStatus status;

    /** Whether all of this task's dependencies and constraints are satisfied, so it can be worked on.
     * Invariant: must equal {@code blockingCount == 0 && openConstraintCount == 0}. Not enforced by
     * this class (a stored field, not derived) - {@code TaskCounterUpdater.withCounters} is the
     * single place responsible for keeping it in sync whenever either counter changes. */
    private final boolean isReady;

    /** Number of not-yet-completed tasks that this task depends on (i.e. tasks blocking it). */
    private final int blockingCount;

    /** Number of unresolved constraints (e.g. scheduling/resource constraints) still open on this task. */
    private final int openConstraintCount;

    private final Integer estimatedDurationMin;
    private final Double score;

    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
    private final LocalDateTime deletedAt;

    @lombok.Builder(builderClassName = "Builder")
    private Task(UUID id,
                 UUID parentTaskId,
                 String ownerId,
                 TaskStatus status,
                 boolean isReady,
                 int blockingCount,
                 int openConstraintCount,
                 Integer estimatedDurationMin,
                 Double score,
                 LocalDateTime createdAt,
                 LocalDateTime updatedAt,
                 LocalDateTime deletedAt) {
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        if (blockingCount < 0) {
            throw new IllegalArgumentException("blockingCount must not be negative");
        }
        if (openConstraintCount < 0) {
            throw new IllegalArgumentException("openConstraintCount must not be negative");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        if (ownerId == null) {
            throw new IllegalArgumentException("ownerId must not be null");
        }
        this.id = id;
        this.parentTaskId = parentTaskId;
        this.ownerId = ownerId;
        this.status = status;
        this.isReady = isReady;
        this.blockingCount = blockingCount;
        this.openConstraintCount = openConstraintCount;
        this.estimatedDurationMin = estimatedDurationMin;
        this.score = score;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.deletedAt = deletedAt;
    }

    /**
     * Returns a new, independent Task with the same field values as {@code source}. Repositories
     * hand out copies (rather than the stored instance) from their read methods, and store a copy
     * on save(), so a caller mutating a Task it received can never change repository state without
     * going through save() - see CLAUDE.md "Open items" (this is a stand-in for the isolation a
     * real persistence layer gives for free).
     */
    public static Task copyOf(Task source) {
        Objects.requireNonNull(source, "source must not be null");
        return new Task(source.id, source.parentTaskId, source.ownerId, source.status,
                source.isReady, source.blockingCount, source.openConstraintCount,
                source.estimatedDurationMin, source.score, source.createdAt, source.updatedAt,
                source.deletedAt);
    }

    @Override
    public String toString() {
        return "Task{id=" + id
                + ", status=" + status
                + ", isReady=" + isReady
                + ", blockingCount=" + blockingCount
                + ", openConstraintCount=" + openConstraintCount
                + ", deletedAt=" + deletedAt
                + "}";
    }
}
