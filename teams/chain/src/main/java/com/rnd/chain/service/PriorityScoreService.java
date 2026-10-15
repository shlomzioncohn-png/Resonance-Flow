package com.rnd.chain.service;

import org.springframework.stereotype.Service;

/**
 * Computes {@code Task.priorityScore} (issue #445): Mind's per-task importance score, adjusted by
 * Chain's own feasibility state, in the 1..10 range Inbox's {@code priority_score} column
 * requires. Pure function of its inputs - no repository access, no side effects - so
 * {@code TaskEligibilityService} is the only place that gathers those inputs (blocking/dependent
 * confidence sums from the dependency graph, openConstraintCount from the task itself) and feeds
 * them in.
 *
 * <h2>Formula</h2>
 * {@code score = null} if {@code mindScore} is null (not yet scored by Mind - see
 * {@link #compute}'s Javadoc). Otherwise:
 * <pre>
 *     m = mindScore * confidence                         (confidence defaults to 1.0 if null)
 *     b = blockingConfidenceSum   / (1 + blockingConfidenceSum)
 *     c = openConstraintCount     / (1 + openConstraintCount)
 *     d = dependentConfidenceSum  / (1 + dependentConfidenceSum)
 *     s = WEIGHT_BLOCKING * (1 - b) + WEIGHT_CONSTRAINTS * (1 - c)
 *         + WEIGHT_DEPENDENTS * d + WEIGHT_MIND * m        // s is in [0, 1]
 *     priority_score = round(1 + 9 * s, 2)                 // always in [1, 10], no clamp needed
 * </pre>
 *
 * <p>{@code blockingConfidenceSum} is the sum of Mind's per-edge confidence (see
 * {@code TaskDependency.confidence}) over this task's <em>unresolved</em> (not-DONE) blockers - a
 * 0.3-confidence blocker should move the score less than a 0.9-confidence one.
 * {@code dependentConfidenceSum} is the same sum over every task that depends on this one
 * (regardless of that dependent's own status): a task other work confidently depends on should
 * rank higher. Both are confidence-weighted sums, not plain counts, and both are normalized with
 * {@code x / (1 + x)} into [0, 1) so an unbounded count can't blow the formula's range - this is
 * why no clamp is needed afterward, unlike an earlier draft of this formula that divided instead
 * of summing weighted terms (see #445 plan history: that version collapsed every heavily-blocked
 * task to the same floor value of 1).
 *
 * <p>Weights (0.4 / 0.3 / 0.2 / 0.1, sum to 1.0) and the normalization function are a team
 * decision (2026-10-06/07, #445 planning), not derived from data - revisit if the demo shows the
 * ranking feels wrong in practice.
 */
@Service
public class PriorityScoreService {

    private static final double WEIGHT_BLOCKING = 0.4;
    private static final double WEIGHT_CONSTRAINTS = 0.3;
    private static final double WEIGHT_DEPENDENTS = 0.2;
    private static final double WEIGHT_MIND = 0.1;

    /** Confidence to assume for {@code confidence == null} - an edge/task with no Mind-supplied
     * confidence is treated as fully trusted, not as zero-weight. */
    private static final double DEFAULT_CONFIDENCE = 1.0;

    /**
     * @param mindScore              Mind's score for the task, 0..1, or null if Mind hasn't
     *                                scored it yet. Null short-circuits to a null result - see
     *                                class Javadoc "NULL: Only when Task.score is empty" (#445
     *                                locked decision).
     * @param confidence              Mind's confidence in {@code mindScore}, 0..1, or null to
     *                                default to full confidence.
     * @param blockingConfidenceSum   sum of Mind's confidence over this task's unresolved
     *                                blockers; must be &gt;= 0.
     * @param openConstraintCount     this task's open constraint count; must be &gt;= 0.
     * @param dependentConfidenceSum  sum of Mind's confidence over tasks that depend on this one;
     *                                must be &gt;= 0.
     * @return the priority score in [1, 10], rounded to two decimal places, or null if
     * {@code mindScore} is null.
     * @throws IllegalArgumentException if any score/confidence is outside [0, 1], or either sum
     * or the constraint count is negative.
     */
    public Double compute(Double mindScore, Double confidence, double blockingConfidenceSum,
                           int openConstraintCount, double dependentConfidenceSum) {
        if (mindScore == null) {
            return null;
        }
        requireInUnitRange(mindScore, "mindScore");
        if (confidence != null) {
            requireInUnitRange(confidence, "confidence");
        }
        if (blockingConfidenceSum < 0) {
            throw new IllegalArgumentException("blockingConfidenceSum must not be negative, got " + blockingConfidenceSum);
        }
        if (openConstraintCount < 0) {
            throw new IllegalArgumentException("openConstraintCount must not be negative, got " + openConstraintCount);
        }
        if (dependentConfidenceSum < 0) {
            throw new IllegalArgumentException("dependentConfidenceSum must not be negative, got " + dependentConfidenceSum);
        }

        double effectiveConfidence = confidence != null ? confidence : DEFAULT_CONFIDENCE;
        double m = mindScore * effectiveConfidence;
        double b = normalize(blockingConfidenceSum);
        double c = normalize(openConstraintCount);
        double d = normalize(dependentConfidenceSum);

        double s = WEIGHT_BLOCKING * (1 - b)
                + WEIGHT_CONSTRAINTS * (1 - c)
                + WEIGHT_DEPENDENTS * d
                + WEIGHT_MIND * m;

        double raw = 1 + 9 * s;
        return Math.round(raw * 100.0) / 100.0;
    }

    /** {@code x / (1 + x)} - maps any non-negative value into [0, 1), monotonically increasing. */
    private static double normalize(double x) {
        return x / (1 + x);
    }

    private static void requireInUnitRange(double value, String name) {
        if (value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(name + " must be between 0 and 1, got " + value);
        }
    }
}
