// Copied verbatim from chain/ruthHoltzman/dependency-repository-66, unmodified.

package com.rnd.chain.dto;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
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

    /** Owning tenant, per issue #580. Nullable: null means "not yet populated" (a legacy task
     * predating this field, or not yet backfilled by Inbox) - never treated as "any tenant". */
    private final UUID tenantId;

    private final TaskStatus status;

    private final Integer estimatedDurationMin;
    private final Double score;

    /** Mind's confidence (0..1) in its own {@link #score} for this task. Null until Mind sends
     * one alongside the score (task.score.proposed). Issue #445: the m term of priority_score
     * is {@code score * confidence}, not {@code score} alone. */
    private final Double confidence;

    /** Chain's feasibility-adjusted priority, 1..10, or null until {@link #score} is set. Issue
     * #445: computed by {@code PriorityScoreService}, never set directly by a caller other than
     * {@code TaskEligibilityService}. */
    private final Double priorityScore;

    /** Latest tier Inbox sent (issue #864); null until Inbox classifies the task. */
    private final TaskTier tier;

    /** When Mind computed the current {@link #score} (task.score.proposed scored_at). Null until
     * a score arrives with one; an event scored earlier than this is ignored. */
    private final LocalDateTime scoredAt;

    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;
    private final LocalDateTime deletedAt;

    @lombok.Builder(builderClassName = "Builder", toBuilder = true)
    private Task(UUID id,
                 UUID parentTaskId,
                 UUID tenantId,
                 TaskStatus status,
                 Integer estimatedDurationMin,
                 Double score,
                 Double confidence,
                 Double priorityScore,
                 TaskTier tier,
                 LocalDateTime scoredAt,
                 LocalDateTime createdAt,
                 LocalDateTime updatedAt,
                 LocalDateTime deletedAt) {
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        this.id = id;
        this.parentTaskId = parentTaskId;
        this.tenantId = tenantId;
        this.status = status;
        this.estimatedDurationMin = estimatedDurationMin;
        this.score = score;
        this.confidence = confidence;
        this.priorityScore = priorityScore;
        this.tier = tier;
        this.scoredAt = scoredAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.deletedAt = deletedAt;
    }

    /**
     * A PENDING stand-in for a task Chain has not received yet, with an epoch {@code updatedAt} so
     * the first real update always wins.
     */
    public static Task placeholder(UUID id, UUID tenantId) {
        return Task.builder()
                .id(id)
                .tenantId(tenantId)
                .status(TaskStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.ofEpochSecond(0, 0, java.time.ZoneOffset.UTC))
                .build();
    }

    @Override
    public String toString() {
        return "Task{id=" + id
                + ", status=" + status
                + ", priorityScore=" + priorityScore
                + ", deletedAt=" + deletedAt
                + "}";
    }
}
