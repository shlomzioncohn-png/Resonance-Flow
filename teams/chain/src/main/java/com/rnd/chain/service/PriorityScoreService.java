package com.rnd.chain.service;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.dto.TaskReadinessSnapshot;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.dto.TaskTier;
import com.rnd.chain.events.SchedulingPublishListener;
import com.rnd.chain.exception.InvalidRequestException;
import com.rnd.chain.exception.TaskNotFoundException;
import com.rnd.chain.exception.TenantReassignmentException;
import com.rnd.chain.repository.TaskDependencyRepository;
import com.rnd.chain.repository.TaskReadinessRepository;
import com.rnd.chain.repository.TaskRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Computes {@code Task.priorityScore} (issue #445): Mind's per-task importance score, adjusted by
 * Chain's own feasibility state, in the 1..10 range Inbox's {@code priority_score} column
 * requires. {@link #compute} is a pure function of its inputs - no repository access, no side
 * effects. {@link #recomputePriorityScoreAndPublish} is the only place that gathers those inputs
 * (blocking/dependent confidence sums from the dependency graph, openConstraintCount from
 * task_readiness), persists the result and queues the scheduling-update publish.
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
@Slf4j
@Service
@RequiredArgsConstructor
public class PriorityScoreService {

    private static final double WEIGHT_BLOCKING = 0.4;
    private static final double WEIGHT_CONSTRAINTS = 0.3;
    private static final double WEIGHT_DEPENDENTS = 0.2;
    private static final double WEIGHT_MIND = 0.1;

    private final TaskRepository taskRepository;
    private final TaskDependencyRepository taskDependencyRepository;
    private final TaskReadinessRepository taskReadinessRepository;
    private final SchedulingUpdateService schedulingUpdateService;
    private final SchedulingPublishListener schedulingPublishListener;

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

    /**
     * Recomputes priorityScore using task_readiness as the authoritative
     * source for openConstraintCount.
     *
     * Publishing is based on the View readiness transition, never on
     * any stored task field.
     */
    @Transactional
    public Task recomputePriorityScoreAndPublish(
            UUID taskId,
            Task oldTask,
            Task current,
            TaskReadinessSnapshot beforeReadiness,
            TaskReadinessSnapshot afterReadiness) {

        Objects.requireNonNull(
                beforeReadiness,
                "beforeReadiness must not be null");

        Objects.requireNonNull(
                afterReadiness,
                "afterReadiness must not be null");

        if (current.getDeletedAt() != null) {
            return current;
        }

        Double recomputed =
                compute(
                        current.getScore(),
                        current.getConfidence(),
                        blockingConfidenceSum(taskId),
                        afterReadiness.openConstraintCount(),
                        dependentConfidenceSum(taskId));

        Task finalTask =
                current;

        if (!Objects.equals(
                recomputed,
                current.getPriorityScore())) {

            finalTask =
                    taskRepository.update(
                                    taskId,
                                    task -> task.toBuilder()
                                            .priorityScore(recomputed)
                                            .build())
                            .orElseThrow(
                                    () -> new TaskNotFoundException(taskId));
        }

        if (oldTask != null) {

            boolean availabilityChanged =
                    beforeReadiness.isReady()
                            != afterReadiness.isReady();

            boolean priorityChanged =
                    !Objects.equals(
                            oldTask.getPriorityScore(),
                            finalTask.getPriorityScore());

            if (availabilityChanged
                    || priorityChanged) {

                schedulingUpdateService
                        .requestPublishAfterCommit(
                                taskId,
                                schedulingPublishListener);
            }
        }

        return finalTask;
    }

    /**
     * Compatibility overload.
     *
     * It is safe only for mutations that do not themselves change the
     * task_readiness View. Readiness and openConstraintCount still come from
     * the View.
     */
    @Transactional
    public Task recomputePriorityScoreAndPublish(
            UUID taskId,
            Task oldTask,
            Task current) {

        TaskReadinessSnapshot readiness =
                requireReadinessSnapshot(taskId);

        return recomputePriorityScoreAndPublish(
                taskId,
                oldTask,
                current,
                readiness,
                readiness);
    }

    /**
     * Applies a Mind score/confidence update.
     *
     * Priority recomputation uses openConstraintCount from task_readiness,
     * not from the Task.
     */
    @Transactional
    public void onScoreProposed(
            UUID taskId,
            Double mindScore,
            Double confidence) {

        Objects.requireNonNull(
                taskId,
                "taskId must not be null");

        requireInUnitRangeIfPresent(
                mindScore,
                "mindScore");

        requireInUnitRangeIfPresent(
                confidence,
                "confidence");

        /*
         * Score/confidence changes do not themselves modify readiness,
         * therefore one View snapshot represents both before and after.
         */
        TaskReadinessSnapshot readiness =
                requireReadinessSnapshot(taskId);

        AtomicReference<Task> previous =
                new AtomicReference<>();

        Task updated =
                taskRepository.update(
                                taskId,
                                task -> {

                                    previous.set(task);

                                    return task.toBuilder()
                                            .score(mindScore)
                                            .confidence(confidence)
                                            .build();
                                })
                        .orElseThrow(
                                () -> new TaskNotFoundException(taskId));

        recomputePriorityScoreAndPublish(
                taskId,
                previous.get(),
                updated,
                readiness,
                readiness);
    }

    /**
     * task.score.proposed entry point: like {@link #onScoreProposed(UUID, Double, Double)}, but
     * creates a PENDING placeholder in {@code tenantId} first if the task is not known yet, and
     * also stores {@code tier} (null leaves the stored one untouched) and {@code scoredAt}.
     *
     * <p>An event whose {@code scoredAt} is earlier than the stored one is ignored entirely (no
     * write, no publish). A null {@code scoredAt} skips that check and keeps the stored value.
     *
     * @throws TenantReassignmentException if the task already has a different, non-null tenant; a
     * still-null stored tenant is backfilled
     */
    @Transactional
    public void onScoreProposed(
            UUID taskId,
            UUID tenantId,
            TaskTier tier,
            Double mindScore,
            Double confidence,
            LocalDateTime scoredAt) {

        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(tenantId, "tenantId must not be null");
        requireInUnitRangeIfPresent(mindScore, "mindScore");
        requireInUnitRangeIfPresent(confidence, "confidence");

        taskRepository.insertIfAbsent(Task.placeholder(taskId, tenantId));

        // Score changes don't modify readiness, so one snapshot serves as both before and after.
        TaskReadinessSnapshot readiness = requireReadinessSnapshot(taskId);

        AtomicReference<Task> previous = new AtomicReference<>();
        AtomicBoolean stale = new AtomicBoolean();
        Task updated = taskRepository.update(taskId, task -> {
                    previous.set(task);
                    if (task.getTenantId() != null && !task.getTenantId().equals(tenantId)) {
                        throw new TenantReassignmentException(
                                "Task " + taskId + " already has tenant " + task.getTenantId()
                                        + ", event claims " + tenantId);
                    }
                    if (scoredAt != null && task.getScoredAt() != null && scoredAt.isBefore(task.getScoredAt())) {
                        stale.set(true);
                        return task;
                    }
                    return task.toBuilder()
                            .tenantId(tenantId)
                            .tier(tier != null ? tier : task.getTier())
                            .score(mindScore)
                            .confidence(confidence)
                            .scoredAt(scoredAt != null ? scoredAt : task.getScoredAt())
                            .build();
                })
                .orElseThrow(() -> new TaskNotFoundException(taskId));

        if (stale.get()) {
            log.info("Score for task {} ignored: scoredAt {} is older than stored {}",
                    taskId, scoredAt, updated.getScoredAt());
            return;
        }

        recomputePriorityScoreAndPublish(taskId, previous.get(), updated, readiness, readiness);
    }

    /**
     * Sum of dependency confidence for direct blockers that are not DONE.
     */
    private double blockingConfidenceSum(
            UUID taskId) {

        double sum =
                0.0;

        for (TaskDependency edge :
                taskDependencyRepository.findByTaskId(taskId)) {

            Task blocker =
                    taskRepository.findById(
                                    edge.dependsOnTaskId())
                            .orElse(null);

            if (blocker == null
                    || blocker.getStatus() != TaskStatus.DONE) {

                sum +=
                        edge.confidence() != null
                                ? edge.confidence()
                                : 1.0;
            }
        }

        return sum;
    }

    /**
     * Sum of confidence for all tasks depending on this task.
     */
    private double dependentConfidenceSum(
            UUID taskId) {

        double sum =
                0.0;

        for (TaskDependency edge :
                taskDependencyRepository
                        .findByDependsOnTaskId(taskId)) {

            sum +=
                    edge.confidence() != null
                            ? edge.confidence()
                            : 1.0;
        }

        return sum;
    }

    private static void requireInUnitRangeIfPresent(
            Double value,
            String name) {

        if (value != null
                && (value < 0.0
                || value > 1.0)) {

            throw new InvalidRequestException(
                    name
                            + " must be between 0 and 1, got "
                            + value);
        }
    }

    private TaskReadinessSnapshot requireReadinessSnapshot(
            UUID taskId) {

        TaskReadinessSnapshot snapshot =
                taskReadinessRepository
                        .findByIds(
                                List.of(taskId))
                        .get(taskId);

        if (snapshot == null) {

            throw new IllegalStateException(
                    "No task_readiness row found for task "
                            + taskId);
        }

        return snapshot;
    }
}
