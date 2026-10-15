package com.rnd.chain.service;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.dto.TaskStateHistoryEntry;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.events.SchedulingPublishListener;
import com.rnd.chain.exception.InvalidRequestException;
import com.rnd.chain.exception.TaskNotFoundException;
import com.rnd.chain.repository.TaskDependencyRepository;
import com.rnd.chain.repository.InMemoryTaskStateHistoryRepository;
import com.rnd.chain.repository.TaskRepository;
import com.rnd.chain.repository.TaskStateHistoryRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

@Component
public class TaskCounterUpdater {

    private static final Logger log =
            LoggerFactory.getLogger(TaskCounterUpdater.class);

    /** {@link TransactionSynchronizationManager} resource key for the per-transaction set of
     * taskIds queued to publish at commit - see {@link #requestPublishAfterCommit}. */
    private static final String PENDING_PUBLISH_RESOURCE_KEY =
            TaskCounterUpdater.class.getName() + ".pendingSchedulingPublishes";

    private final TaskRepository taskRepository;
    private final TaskDependencyRepository taskDependencyRepository;
    private final PriorityScoreService priorityScoreService;
    private final SchedulingPublishListener schedulingPublishListener;
    private final TaskStateHistoryRepository taskStateHistoryRepository;
    private final Clock clock;

    @Autowired
    public TaskCounterUpdater(
            TaskRepository taskRepository,
            TaskDependencyRepository taskDependencyRepository,
            PriorityScoreService priorityScoreService,
            SchedulingPublishListener schedulingPublishListener,
            TaskStateHistoryRepository taskStateHistoryRepository,
            Clock clock) {

        this.taskRepository = taskRepository;
        this.taskDependencyRepository = taskDependencyRepository;
        this.priorityScoreService = priorityScoreService;
        this.schedulingPublishListener = schedulingPublishListener;
        this.taskStateHistoryRepository = taskStateHistoryRepository;
        this.clock = clock;
    }

    /** For tests that don't care about history: records into a throwaway in-memory repository. */
    public TaskCounterUpdater(
            TaskRepository taskRepository,
            TaskDependencyRepository taskDependencyRepository,
            PriorityScoreService priorityScoreService,
            SchedulingPublishListener schedulingPublishListener) {

        this(taskRepository, taskDependencyRepository, priorityScoreService, schedulingPublishListener,
                new InMemoryTaskStateHistoryRepository(), Clock.systemUTC());
    }

    /**
     * Issue #774: appends a STATUS_CHANGED history row. Call only after the status write actually
     * happened, inside the same transaction; a no-op when the status did not change.
     */
    public void recordStatusChange(UUID taskId, TaskStatus from, TaskStatus to) {
        if (from == to) {
            return;
        }
        taskStateHistoryRepository.record(TaskStateHistoryEntry.statusChanged(
                UUID.randomUUID(), taskId, from, to, Instant.now(clock)));
    }

    /**
     * Issue #774: appends BLOCKED (0 to positive) or UNBLOCKED (positive to 0) for a blockingCount
     * change that was just written; any other change (e.g. 1 to 2) records nothing.
     */
    public void recordBlockingTransition(UUID taskId, int before, int after) {
        if (before == 0 && after > 0) {
            taskStateHistoryRepository.record(TaskStateHistoryEntry.blocked(
                    UUID.randomUUID(), taskId, after, Instant.now(clock)));
        } else if (before > 0 && after == 0) {
            taskStateHistoryRepository.record(TaskStateHistoryEntry.unblocked(
                    UUID.randomUUID(), taskId, before, Instant.now(clock)));
        }
    }

    public Task requireTask(UUID taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));
    }

    public static <T> T requireUpdated(
            Optional<T> updated,
            UUID taskId) {

        return updated.orElseThrow(
                () -> new TaskNotFoundException(taskId));
    }

    public static Task.Builder copyOf(Task task) {
        return Task.builder()
                .id(task.getId())
                .parentTaskId(task.getParentTaskId())
                .tenantId(task.getTenantId())
                .status(task.getStatus())
                .isReady(task.isReady())
                .blockingCount(task.getBlockingCount())
                .openConstraintCount(task.getOpenConstraintCount())
                .estimatedDurationMin(task.getEstimatedDurationMin())
                .score(task.getScore())
                .confidence(task.getConfidence())
                .priorityScore(task.getPriorityScore())
                .createdAt(task.getCreatedAt())
                .updatedAt(task.getUpdatedAt())
                .deletedAt(task.getDeletedAt());
    }

    public static Task withCounters(
            Task task,
            int blockingCount,
            int openConstraintCount) {

        boolean isReady = blockingCount == 0
                && openConstraintCount == 0;

        if (task.getBlockingCount() == blockingCount
                && task.getOpenConstraintCount() == openConstraintCount
                && task.isReady() == isReady) {

            return task;
        }

        return copyOf(task)
                .blockingCount(blockingCount)
                .openConstraintCount(openConstraintCount)
                .isReady(isReady)
                .build();
    }

    @Transactional
    public Task updateCounters(
            UUID taskId,
            UnaryOperator<Task> countersMutator) {

        AtomicReference<Task> previous = new AtomicReference<>();

        Task updated = requireUpdated(
                taskRepository.update(
                        taskId,
                        task -> {
                            previous.set(task);
                            return countersMutator.apply(task);
                        }),
                taskId);

        Task oldTask = previous.get();
        recordBlockingTransition(taskId, oldTask.getBlockingCount(), updated.getBlockingCount());

        if (updated != oldTask) {
            log.info(
                    "Task {} eligibility updated: "
                            + "blockingCount={}, "
                            + "openConstraintCount={}, "
                            + "isReady={}",
                    taskId,
                    updated.getBlockingCount(),
                    updated.getOpenConstraintCount(),
                    updated.isReady());
        }

        return recomputePriorityScoreAndPublish(taskId, oldTask, updated);
    }

    @Transactional
    public void adjustBlockingCount(
            UUID taskId,
            boolean dependsOnIsDone,
            int delta) {

        int effectiveDelta = dependsOnIsDone ? 0 : delta;

        updateCounters(
                taskId,
                task -> withCounters(
                        task,
                        Math.max(
                                0,
                                task.getBlockingCount()
                                        + effectiveDelta),
                        task.getOpenConstraintCount()));
    }

    /**
     * Issue #445: recomputes {@code priorityScore} for {@code current} from its latest
     * blocking/dependent/constraint state, persists it if it changed, and publishes
     * {@code task.scheduling.updated} if either {@code isReady} or {@code priorityScore} differs
     * from {@code oldTask} - the locked decision is "any change to priority_score or
     * is_available", not just is_available.
     *
     * <p>Soft-deleted tasks are skipped entirely (no calculation, no publish - locked decision).
     * {@code oldTask} is the pre-mutation snapshot; null means "no prior state to compare against"
     * (e.g. a fresh task), in which case nothing is published - there's no change to report yet.
     *
     * <p>Public (not private) because {@code DependencyEdgeService} also calls this directly for
     * the far end of a changed edge - see {@code DependencyEdgeService#recomputeDependencyTargetPriority}
     * - whose own blocking/openConstraintCount are untouched by that edge change, so it has
     * nothing to pass {@link #updateCounters} as a counters mutator.
     */
    @Transactional
    public Task recomputePriorityScoreAndPublish(UUID taskId, Task oldTask, Task current) {
        if (current.getDeletedAt() != null) {
            return current;
        }

        Double recomputed = priorityScoreService.compute(
                current.getScore(),
                current.getConfidence(),
                blockingConfidenceSum(taskId),
                current.getOpenConstraintCount(),
                dependentConfidenceSum(taskId));

        Task finalTask = current;
        if (!Objects.equals(recomputed, current.getPriorityScore())) {
            finalTask = requireUpdated(
                    taskRepository.update(taskId, task -> copyOf(task).priorityScore(recomputed).build()),
                    taskId);
        }

        if (oldTask != null) {
            boolean availabilityChanged = oldTask.isReady() != finalTask.isReady();
            boolean priorityChanged = !Objects.equals(oldTask.getPriorityScore(), finalTask.getPriorityScore());
            if (availabilityChanged || priorityChanged) {
                requestPublishAfterCommit(taskId);
            }
        }

        return finalTask;
    }

    /**
     * Sum of Mind's confidence (see {@link TaskDependency#confidence()}) over {@code taskId}'s
     * direct blockers that are not DONE - a DONE blocker no longer counts against readiness, so it
     * shouldn't count against priority either. An edge with no stored confidence contributes
     * {@link PriorityScoreService}'s default (full confidence), same as everywhere else.
     */
    private double blockingConfidenceSum(UUID taskId) {
        double sum = 0.0;
        for (TaskDependency edge : taskDependencyRepository.findByTaskId(taskId)) {
            Task blocker = taskRepository.findById(edge.dependsOnTaskId()).orElse(null);
            if (blocker == null || blocker.getStatus() != TaskStatus.DONE) {
                sum += edge.confidence() != null ? edge.confidence() : 1.0;
            }
        }
        return sum;
    }

    /**
     * Sum of Mind's confidence over every edge where some other task depends on {@code taskId},
     * regardless of that dependent's own status - a task other work confidently depends on is
     * important independent of whether that other work is itself currently blocked.
     */
    private double dependentConfidenceSum(UUID taskId) {
        double sum = 0.0;
        for (TaskDependency edge : taskDependencyRepository.findByDependsOnTaskId(taskId)) {
            sum += edge.confidence() != null ? edge.confidence() : 1.0;
        }
        return sum;
    }

    /**
     * Issue #445 Phase 3: queues {@code taskId} to publish once the current transaction commits -
     * never synchronously, and never at all if the transaction rolls back. Every public mutator
     * that can reach here is {@code @Transactional} (enforced by a reflection-based guardrail
     * test across the split-out service classes), so in production a transaction is always
     * active here; see the early-return below for what happens if that invariant is ever
     * violated.
     *
     * <p><b>Deduplication "by taskId within the transaction"</b> (locked decision): a
     * {@link LinkedHashSet} bound to the current transaction via
     * {@link TransactionSynchronizationManager} - {@code taskId} is added to it, and only the
     * first time a given id is added in this transaction does a {@link TransactionSynchronization}
     * get registered to flush the whole set at commit. So a task touched several times in one
     * transaction still publishes at most once for it, with its final, post-commit state (the
     * callback re-reads the task - see {@link SchedulingPublishListener#onCommit} - never a
     * snapshot taken mid-transaction).
     *
     * <p><b>No synchronous fallback</b> ({@code fallbackExecution=false}, locked decision): if
     * there is no active transaction when this is called, the publish is dropped (logged at
     * ERROR, not silently) rather than fired immediately. The alternative (publish immediately)
     * would mask exactly the bug this is meant to catch - a mutator that forgot
     * {@code @Transactional} - behind "it happened to work anyway".
     */
    private void requestPublishAfterCommit(UUID taskId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            log.error("requestPublishAfterCommit({}) called with no active transaction - this "
                    + "publish is dropped, not sent. Every public mutator that can reach this "
                    + "must be @Transactional for issue #445's AFTER_COMMIT publishing to work.", taskId);
            return;
        }

        @SuppressWarnings("unchecked")
        Set<UUID> pending = (Set<UUID>) TransactionSynchronizationManager.getResource(PENDING_PUBLISH_RESOURCE_KEY);
        if (pending == null) {
            pending = new LinkedHashSet<>();
            TransactionSynchronizationManager.bindResource(PENDING_PUBLISH_RESOURCE_KEY, pending);
        }

        if (!pending.add(taskId)) {
            return; // already queued in this transaction - dedup, see Javadoc above.
        }

        if (pending.size() == 1) {
            // First id queued in this transaction - register the one-time flush/cleanup hooks.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    @SuppressWarnings("unchecked")
                    Set<UUID> idsToPublish =
                            (Set<UUID>) TransactionSynchronizationManager.getResource(PENDING_PUBLISH_RESOURCE_KEY);
                    for (UUID id : idsToPublish) {
                        schedulingPublishListener.onCommit(id);
                    }
                }

                @Override
                public void afterCompletion(int status) {
                    // Runs after afterCommit() on commit, or alone on rollback - either way, the
                    // next transaction on this thread must not inherit a stale pending set.
                    TransactionSynchronizationManager.unbindResourceIfPossible(PENDING_PUBLISH_RESOURCE_KEY);
                }
            });
        }
    }

    /**
     * Issue #445: receives Mind's score for a task (the event is {@code task.score.proposed} -
     * wiring an actual Kafka consumer onto this method is #386, out of scope here). Updates
     * {@code Task.score}/{@code confidence} and recomputes/publishes {@code priorityScore}
     * accordingly.
     *
     * <p>{@code mindScore == null} is a legitimate "Mind cleared this task's score", not an
     * error: it clears both {@code score} and {@code confidence} (a confidence without a score is
     * meaningless) and - per the #445 locked decision - still publishes the clear, since
     * {@code priorityScore} flipping to null is itself a priority change like any other.
     *
     * @throws InvalidRequestException if {@code mindScore} or {@code confidence} is non-null and
     * outside [0, 1] - the locked decision is to reject, not silently clamp, so this is meant to
     * route to the DLT once #386 wires a real consumer on top of this method.
     * @throws TaskNotFoundException if no task with {@code taskId} exists, or it's soft-deleted
     */
    @Transactional
    public void onScoreProposed(UUID taskId, Double mindScore, Double confidence) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        requireInUnitRangeIfPresent(mindScore, "mindScore");
        requireInUnitRangeIfPresent(confidence, "confidence");

        AtomicReference<Task> previous = new AtomicReference<>();
        Task updated = requireUpdated(
                taskRepository.update(taskId, task -> {
                    previous.set(task);
                    return copyOf(task).score(mindScore).confidence(confidence).build();
                }),
                taskId);

        recomputePriorityScoreAndPublish(taskId, previous.get(), updated);
    }

    private static void requireInUnitRangeIfPresent(Double value, String name) {
        if (value != null && (value < 0.0 || value > 1.0)) {
            throw new InvalidRequestException(name + " must be between 0 and 1, got " + value);
        }
    }
}
