package com.rnd.chain.service;

import com.rnd.chain.dto.Constraint;
import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.dto.TaskEligibilityResponse;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.exception.DependencyNotFoundException;
import com.rnd.chain.exception.DuplicateDependencyException;
import com.rnd.chain.exception.InvalidDependencyException;
import com.rnd.chain.exception.InvalidRequestException;
import com.rnd.chain.exception.TaskNotFoundException;
import com.rnd.chain.event.SchedulingPublishListener;
import com.rnd.chain.repository.ConstraintRepository;
import com.rnd.chain.repository.TaskDependencyRepository;
import com.rnd.chain.repository.TaskRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.rnd.chain.dto.TaskEligibilityResponse;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.concurrent.atomic.AtomicReference;

/**
 * See CLAUDE.md "Open items" for the locking rationale: dependencyGraphLock guards the
 * dependency edges plus any DONE-status read that decides whether an edge currently counts
 * against blockingCount. Per-task field mutations (blockingCount, openConstraintCount, status,
 * isReady) no longer need a hand-rolled per-task lock - {@link TaskRepository#update} already
 * performs an atomic per-id read-modify-write, so a single Task's fields can never be lost to a
 * race the way a separate findById/mutate/save pair could. dependencyGraphLock is still required
 * for decisions that span more than one Task/edge at once (cycle detection, and reading a
 * dependency's DONE status together with creating/removing the edge to it).
 *
 * <p>{@code @Transactional} on the methods below now provides real atomicity and rollback -
 * {@link #onTaskStatusChanged} holds a row lock on the changed task and every one of its
 * dependents simultaneously until it returns, so they all commit or roll back together.
 */
@Service
public class TaskEligibilityService {

    private static final Logger log =
            LoggerFactory.getLogger(TaskEligibilityService.class);

    private final TaskRepository taskRepository;
    private final TaskDependencyRepository taskDependencyRepository;
    private final ConstraintRepository constraintRepository;
    private final PriorityScoreService priorityScoreService;
    private final SchedulingPublishListener schedulingPublishListener;

    private final Object dependencyGraphLock = new Object();

    /** {@link TransactionSynchronizationManager} resource key for the per-transaction set of
     * taskIds queued to publish at commit - see {@link #requestPublishAfterCommit}. */
    private static final String PENDING_PUBLISH_RESOURCE_KEY =
            TaskEligibilityService.class.getName() + ".pendingSchedulingPublishes";

    /**
     * Issue #445 Phase 3: the constructor Spring actually wires up - {@code @Autowired} picks
     * this one over the compat overloads below (Spring's single-constructor auto-detection only
     * applies when there is exactly one constructor; with several, one of them must say which).
     * No longer takes {@link SchedulingUpdateService} directly - publishing goes exclusively
     * through {@code schedulingPublishListener}, after commit, never synchronously from this
     * class (see {@link #requestPublishAfterCommit}).
     */
    @org.springframework.beans.factory.annotation.Autowired
    public TaskEligibilityService(
            TaskRepository taskRepository,
            TaskDependencyRepository taskDependencyRepository,
            ConstraintRepository constraintRepository,
            PriorityScoreService priorityScoreService,
            SchedulingPublishListener schedulingPublishListener) {

        this.taskRepository = taskRepository;
        this.taskDependencyRepository = taskDependencyRepository;
        this.constraintRepository = constraintRepository;
        this.priorityScoreService = priorityScoreService;
        this.schedulingPublishListener = schedulingPublishListener;
    }

    /**
     * Issue #445 Phase 1 compat: same as the pre-Phase-3 5-arg constructor (which took a
     * {@link SchedulingUpdateService} directly), building its own {@link SchedulingPublishListener}
     * from it so every existing test that still constructs this class that way keeps compiling.
     * Prefer the {@code @Autowired} constructor above in new code.
     */
    public TaskEligibilityService(
            TaskRepository taskRepository,
            TaskDependencyRepository taskDependencyRepository,
            SchedulingUpdateService schedulingUpdateService,
            ConstraintRepository constraintRepository,
            PriorityScoreService priorityScoreService) {

        this(taskRepository, taskDependencyRepository, constraintRepository, priorityScoreService,
                new SchedulingPublishListener(taskRepository, schedulingUpdateService));
    }

    /**
     * Issue #445 Phase 1 compat: same as the original (pre-#445) 4-arg constructor. Kept only so
     * the many existing tests that construct this class directly with 4 args don't all need
     * updating; prefer the {@code @Autowired} constructor above in new code.
     */
    public TaskEligibilityService(
            TaskRepository taskRepository,
            TaskDependencyRepository taskDependencyRepository,
            SchedulingUpdateService schedulingUpdateService,
            ConstraintRepository constraintRepository) {

        this(taskRepository, taskDependencyRepository, schedulingUpdateService, constraintRepository,
                new PriorityScoreService());
    }

    private Task requireTask(UUID taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));
    }

    private static <T> T requireUpdated(
            Optional<T> updated,
            UUID taskId) {

        return updated.orElseThrow(
                () -> new TaskNotFoundException(taskId));
    }

    private static Task.Builder copyOf(Task task) {
        return Task.builder()
                .id(task.getId())
                .parentTaskId(task.getParentTaskId())
                .ownerId(task.getOwnerId())
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

    private static Task withCounters(
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

    private Task updateCounters(
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

    /**
     * Issue #445: recomputes {@code priorityScore} for {@code current} from its latest
     * blocking/dependent/constraint state, persists it if it changed, and publishes
     * {@code task.scheduling.updated} if either {@code isReady} or {@code priorityScore} differs
     * from {@code oldTask} - the locked decision is "any change to priority_score or
     * is_available", not just is_available the way this class published before #445.
     *
     * <p>Soft-deleted tasks are skipped entirely (no calculation, no publish - locked decision).
     * {@code oldTask} is the pre-mutation snapshot; null means "no prior state to compare against"
     * (e.g. a fresh task), in which case nothing is published - there's no change to report yet.
     *
     * <p>Not atomic with the counters update that produced {@code current}: recomputation reads
     * the dependency graph in a separate step, under no lock here (score-only changes don't touch
     * edges, and a graph change always goes through one of {@code addDependency}/
     * {@code removeDependency}/{@code onTaskStatusChanged...}, each of which already holds
     * {@code dependencyGraphLock} for its own mutation and calls back into this method while still
     * holding it - see those methods). A known, accepted gap for Phase 1 - see CLAUDE.md "Open
     * items" for the general pattern this class already lives with until real persistence makes
     * every one of these a single atomic statement.
     */
    private Task recomputePriorityScoreAndPublish(UUID taskId, Task oldTask, Task current) {
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
     * Issue #445 Phase 3: queues {@code taskId} to publish once the current transaction commits -
     * never synchronously, and never at all if the transaction rolls back. Every public mutator on
     * this class is {@code @Transactional} (enforced by {@code TaskEligibilityServiceTransactionalCoverageTest}),
     * so in production a transaction is always active here; see the {@code else} branch for what
     * happens if that invariant is ever violated.
     *
     * <p><b>Deduplication "by taskId within the transaction"</b> (locked decision): a
     * {@link LinkedHashSet} bound to the current transaction via
     * {@link TransactionSynchronizationManager} - {@code taskId} is added to it, and only the
     * first time a given id is added in this transaction does a {@link TransactionSynchronization}
     * get registered to flush the whole set at commit. So a task touched five times in one
     * transaction (e.g. {@link #addDependencies}'s batch loop) still publishes at most once for
     * it, with its final, post-commit state (the callback re-reads the task - see
     * {@link SchedulingPublishListener#onCommit} - never a snapshot taken mid-transaction).
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
                    + "publish is dropped, not sent. Every public mutator on this class must be "
                    + "@Transactional for issue #445's AFTER_COMMIT publishing to work.", taskId);
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
            // (Registered once per transaction, not once per id: re-registering on every add
            // would flush/publish the same ids multiple times at commit.)
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

    @Transactional
    public void recalculateTaskEligibility(UUID taskId) {

        Objects.requireNonNull(
                taskId,
                "taskId must not be null");

        updateCounters(
                taskId,
                task -> withCounters(
                        task,
                        task.getBlockingCount(),
                        task.getOpenConstraintCount()));
    }

    /** Same as {@link #addDependency(UUID, UUID, Double)}, with no Mind confidence to carry -
     * defaults to full confidence (1.0), same as every other USER-facing call site in this
     * class. */
    @Transactional
    public TaskDependency addDependency(
            UUID taskId,
            UUID dependsOnTaskId) {

        return addDependency(taskId, dependsOnTaskId, 1.0);
    }

    /**
     * Same as {@link #addDependency(UUID, UUID)}, with an explicit Mind confidence (issue #445)
     * for this specific edge instead of defaulting to full confidence. Used by
     * {@code TaskDependencyService.addDependency}'s own confidence-aware overload, which
     * {@code DependencyProposedEventHandler} calls for a dependency whose target task is already
     * known - see that handler's class for the known gap on the "target not seen yet" path
     * (confidence isn't preserved through {@code UnresolvedDependencyService} today).
     *
     * @throws InvalidDependencyException if {@code confidence} is non-null and outside [0, 1], or
     * the edge would create a cycle
     */
    @Transactional
    public TaskDependency addDependency(
            UUID taskId,
            UUID dependsOnTaskId,
            Double confidence) {

        Objects.requireNonNull(
                taskId,
                "taskId must not be null");

        Objects.requireNonNull(
                dependsOnTaskId,
                "dependsOnTaskId must not be null");

        if (confidence != null && (confidence < 0.0 || confidence > 1.0)) {
            throw new InvalidDependencyException(
                    "confidence for dependency " + taskId + " -> " + dependsOnTaskId
                            + " must be between 0 and 1, got " + confidence);
        }

        synchronized (dependencyGraphLock) {

            // Checked before the cycle check and the task lookups: a duplicate must not require
            // either task to still exist, and an existing edge cannot be introducing a new cycle.
            if (taskDependencyRepository.existsDependency(taskId, dependsOnTaskId)) {
                throw new DuplicateDependencyException(
                        "Task " + taskId + " already depends on " + dependsOnTaskId);
            }

            if (wouldCreateCycle(
                    taskId,
                    dependsOnTaskId)) {

                throw new InvalidDependencyException(
                        "Dependency would create a cycle: "
                                + taskId
                                + " -> "
                                + dependsOnTaskId);
            }

            Task task = requireTask(taskId);
            Task dependencyTask = requireTask(dependsOnTaskId);

            Optional<TaskDependency> saved =
                    taskDependencyRepository.save(
                            new TaskDependency(
                                    taskId,
                                    dependsOnTaskId,
                                    confidence));

            TaskDependency dependency = saved.orElseThrow(
                    () -> new DuplicateDependencyException(
                            "Task "
                                    + taskId
                                    + " already depends on "
                                    + dependsOnTaskId));

            boolean dependencyAlreadyDone =
                    dependencyTask.getStatus() == TaskStatus.DONE;

            adjustBlockingCount(
                    task.getId(),
                    dependencyAlreadyDone,
                    1);
            recomputeDependencyTargetPriority(dependsOnTaskId);

            return dependency;
        }
    }

    /**
     * Issue #445: {@code dependsOnTaskId} just gained (or lost, or had its confidence changed on)
     * a dependent - its own {@code blockingCount}/{@code openConstraintCount} are untouched, but
     * {@code dependentConfidenceSum(dependsOnTaskId)} changed, which can move its priorityScore.
     * A no-op counters mutator routes it through the same {@link #recomputePriorityScoreAndPublish}
     * every other change goes through, rather than duplicating that decision here.
     */
    private void recomputeDependencyTargetPriority(UUID dependsOnTaskId) {
        updateCounters(dependsOnTaskId, UnaryOperator.identity());
    }

    private record PendingEdge(UUID dependsOnTaskId, Double confidence, boolean isNewEdge,
                                boolean dependsOnAlreadyDone) {
    }

    /** Cycle/existence checks only - no writes. An edge that already exists is a redelivery and
     * comes back as a no-op for blockingCount purposes ({@code isNewEdge == false}), not an error
     * - though issue #445's {@link #applyEdge} still upserts its confidence. */
    private PendingEdge validateEdge(UUID taskId, UUID dependsOnTaskId, Double confidence) {
        if (taskDependencyRepository.existsDependency(taskId, dependsOnTaskId)) {
            return new PendingEdge(dependsOnTaskId, confidence, false, false);
        }
        if (wouldCreateCycle(taskId, dependsOnTaskId)) {
            throw new InvalidDependencyException(
                    "Dependency would create a cycle: " + taskId + " -> " + dependsOnTaskId);
        }
        requireTask(taskId);
        boolean dependsOnAlreadyDone = requireTask(dependsOnTaskId).getStatus() == TaskStatus.DONE;
        return new PendingEdge(dependsOnTaskId, confidence, true, dependsOnAlreadyDone);
    }

    /** Writes one already-validated edge - no cycle/existence checks, see {@link #validateEdge}. */
    private void applyEdge(UUID taskId, PendingEdge edge) {
        // Issue #445: always save, even for a redelivery - TaskDependencyRepository#save upserts
        // confidence on an already-existing edge (Mind can republish the same task with an
        // updated confidence; it only ever adds/updates, never removes).
        taskDependencyRepository.save(new TaskDependency(taskId, edge.dependsOnTaskId(), edge.confidence()));
        if (!edge.isNewEdge()) {
            log.info("Dependency {} -> {} already existed, blockingCount left unchanged, confidence updated to {}",
                    taskId, edge.dependsOnTaskId(), edge.confidence());
            // blockingCount doesn't change, but the confidence update above can still move both
            // ends' priority_score (taskId's blockingConfidenceSum, dependsOnTaskId's
            // dependentConfidenceSum) - recompute both without touching any counters.
            updateCounters(taskId, UnaryOperator.identity());
            recomputeDependencyTargetPriority(edge.dependsOnTaskId());
            return;
        }
        adjustBlockingCount(taskId, edge.dependsOnAlreadyDone(), 1);
        recomputeDependencyTargetPriority(edge.dependsOnTaskId());
    }

    /**
     * Same as {@link #addDependencies(UUID, Map)}, for a caller with no per-edge Mind confidence
     * (e.g. a plain id list) - every edge defaults to full confidence (1.0).
     */
    @Transactional
    public void addDependencies(UUID taskId, List<UUID> dependsOnTaskIds) {
        Objects.requireNonNull(dependsOnTaskIds, "dependsOnTaskIds must not be null");
        Map<UUID, Double> withDefaultConfidence = new LinkedHashMap<>();
        for (UUID id : dependsOnTaskIds) {
            Objects.requireNonNull(id, "dependsOnTaskIds must not contain null elements");
            withDefaultConfidence.put(id, 1.0);
        }
        addDependencies(taskId, withDefaultConfidence);
    }

    /**
     * Same effect as calling {@link #addDependency} once per entry, but validates every entry
     * against the current graph state before applying any of them, so one invalid entry (a
     * cycle, or a dependency target Chain hasn't seen yet) fails the whole batch with zero
     * partial effect (PR #467 review, on the Kafka handler that batches a task.dependencies.proposed
     * event's array through this method). Entries that already exist have their confidence
     * upserted (issue #445) rather than being pure no-ops - see {@link #applyEdge}.
     *
     * @param dependsOnTaskIdsWithConfidence maps each blocker's id to Mind's confidence (0..1) in
     * that specific edge. Duplicate ids aren't possible (it's a Map); a null value is rejected.
     *
     * <p>Validating up front is safe because {@link #wouldCreateCycle} only traverses edges that
     * already exist before this call, and every same-batch edge starts at {@code taskId}. The whole
     * batch runs under one {@code dependencyGraphLock} acquisition, so no concurrent writer can
     * invalidate a validated entry before it is applied.
     */
    @Transactional
    public void addDependencies(UUID taskId, Map<UUID, Double> dependsOnTaskIdsWithConfidence) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(dependsOnTaskIdsWithConfidence, "dependsOnTaskIdsWithConfidence must not be null");
        dependsOnTaskIdsWithConfidence.forEach((id, confidence) -> {
            Objects.requireNonNull(id, "dependsOnTaskIdsWithConfidence must not contain a null id");
            if (confidence == null || confidence < 0.0 || confidence > 1.0) {
                throw new InvalidDependencyException(
                        "confidence for dependency " + taskId + " -> " + id
                                + " must be between 0 and 1, got " + confidence);
            }
        });
        log.info("Adding {} distinct dependency(ies) for task {}", dependsOnTaskIdsWithConfidence.size(), taskId);

        synchronized (dependencyGraphLock) {
            List<PendingEdge> validated = new ArrayList<>(dependsOnTaskIdsWithConfidence.size());
            for (Map.Entry<UUID, Double> entry : dependsOnTaskIdsWithConfidence.entrySet()) {
                validated.add(validateEdge(taskId, entry.getKey(), entry.getValue()));
            }
            for (PendingEdge edge : validated) {
                applyEdge(taskId, edge);
            }
        }
    }

    @Transactional
    public void removeDependency(
            UUID taskId,
            UUID dependsOnTaskId) {

        Objects.requireNonNull(
                taskId,
                "taskId must not be null");

        Objects.requireNonNull(
                dependsOnTaskId,
                "dependsOnTaskId must not be null");

        synchronized (dependencyGraphLock) {

            requireTask(taskId);

            Task dependencyTask = requireTask(dependsOnTaskId);

            boolean removed = taskDependencyRepository.delete(
                    taskId,
                    dependsOnTaskId);

            if (!removed) {
                throw new DependencyNotFoundException(
                        "No dependency from "
                                + taskId
                                + " to "
                                + dependsOnTaskId);
            }

            boolean dependencyCurrentlyDone =
                    dependencyTask.getStatus() == TaskStatus.DONE;

            adjustBlockingCount(
                    taskId,
                    dependencyCurrentlyDone,
                    -1);
            recomputeDependencyTargetPriority(dependsOnTaskId);
        }
    }

    private void adjustBlockingCount(
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

    private boolean wouldCreateCycle(
            UUID taskId,
            UUID dependsOnTaskId) {

        Set<UUID> visited = new HashSet<>();
        Deque<UUID> toVisit = new ArrayDeque<>();

        toVisit.push(dependsOnTaskId);

        while (!toVisit.isEmpty()) {

            UUID current = toVisit.pop();

            if (current.equals(taskId)) {
                return true;
            }

            if (!visited.add(current)) {
                continue;
            }

            // Under dependencyGraphLock, so the repository's own read lock is redundant here.
            for (TaskDependency dependency :
                    taskDependencyRepository.findByTaskIdUnderExternalLock(current)) {

                toVisit.push(
                        dependency.dependsOnTaskId());
            }
        }

        return false;
    }

    @Transactional
    public void onTaskStatusChanged(
            UUID taskId,
            TaskStatus newStatus) {

        Objects.requireNonNull(
                taskId,
                "taskId must not be null");

        Objects.requireNonNull(
                newStatus,
                "newStatus must not be null");

        synchronized (dependencyGraphLock) {

            AtomicReference<Task> previous =
                    new AtomicReference<>();

            requireUpdated(
                    taskRepository.update(
                            taskId,
                            task -> {
                                previous.set(task);

                                return copyOf(task)
                                        .status(newStatus)
                                        .build();
                            }),
                    taskId);

            TaskStatus previousStatus =
                    previous.get().getStatus();

            if (previousStatus != TaskStatus.DONE
                    && newStatus == TaskStatus.DONE) {

                adjustDependentsBlockingCount(
                        taskId,
                        -1);

            } else if (previousStatus == TaskStatus.DONE
                    && newStatus != TaskStatus.DONE) {

                adjustDependentsBlockingCount(
                        taskId,
                        1);
            }
        }
    }

    /**
     * Same status-change effect as {@link #onTaskStatusChanged}, but for a producer (task.updated)
     * whose events carry no id and must be ordered by the {@code updatedAt} on the event itself.
     * The staleness check, status write, and {@code updatedAt} stamp all happen inside one
     * {@code dependencyGraphLock} critical section, so a redelivered or out-of-order event cannot
     * interleave between them (PR #467 review).
     *
     * @throws TaskNotFoundException if no task with {@code taskId} exists
     */
    @Transactional
    public void onTaskStatusChangedIfNewer(UUID taskId, TaskStatus newStatus, LocalDateTime eventUpdatedAt) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(newStatus, "newStatus must not be null");
        Objects.requireNonNull(eventUpdatedAt, "eventUpdatedAt must not be null");

        synchronized (dependencyGraphLock) {
            Task current = requireTask(taskId);
            if (current.getUpdatedAt() != null && !eventUpdatedAt.isAfter(current.getUpdatedAt())) {
                log.debug("Task {} status update ignored: event updatedAt {} is not newer than stored {}",
                        taskId, eventUpdatedAt, current.getUpdatedAt());
                return;
            }

            log.info("Task {} status changed to {}", taskId, newStatus);
            TaskStatus previousStatus = current.getStatus();
            requireUpdated(taskRepository.update(taskId, task ->
                    copyOf(task).status(newStatus).updatedAt(eventUpdatedAt).build()), taskId);

            if (previousStatus != TaskStatus.DONE && newStatus == TaskStatus.DONE) {
                adjustDependentsBlockingCount(taskId, -1);
            } else if (previousStatus == TaskStatus.DONE && newStatus != TaskStatus.DONE) {
                adjustDependentsBlockingCount(taskId, 1);
            }
        }
    }

    /**
     * Applies task.completed. The Kafka record timestamp is only compared against the stored
     * updatedAt (Inbox's clock) and never written to it, so a later task.updated is not skewed.
     *
     * @throws TaskNotFoundException if no task with {@code taskId} exists
     */
    @Transactional
    public void onTaskCompleted(UUID taskId, LocalDateTime kafkaTimestamp) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(kafkaTimestamp, "kafkaTimestamp must not be null");

        synchronized (dependencyGraphLock) {
            Task current = requireTask(taskId);
            if (current.getStatus() == TaskStatus.DONE) {
                return;
            }
            if (current.getUpdatedAt() != null && !kafkaTimestamp.isAfter(current.getUpdatedAt())) {
                log.debug("Task {} completion ignored: record timestamp {} is not newer than stored {}",
                        taskId, kafkaTimestamp, current.getUpdatedAt());
                return;
            }

            log.info("Task {} completed", taskId);
            requireUpdated(taskRepository.update(taskId, task ->
                    copyOf(task).status(TaskStatus.DONE).build()), taskId);
            adjustDependentsBlockingCount(taskId, -1);
        }
    }

    private void adjustDependentsBlockingCount(
            UUID taskId,
            int delta) {

        List<TaskDependency> dependents =
                taskDependencyRepository
                        .findByDependsOnTaskId(taskId);

        List<UUID> dependentIds = dependents.stream().map(TaskDependency::taskId).toList();

        Map<UUID, Task> previousById = new HashMap<>();
        List<Task> updated = taskRepository.updateAll(dependentIds, task -> {
            previousById.put(task.getId(), task);
            return withCounters(
                    task,
                    Math.max(0, task.getBlockingCount() + delta),
                    task.getOpenConstraintCount());
        });

        // Issue #445: each dependent's blockingCount just changed, which can move its own
        // priority_score (see blockingConfidenceSum) even on top of whatever isReady did -
        // recomputePriorityScoreAndPublish decides whether that warrants a publish.
        for (Task task : updated) {
            recomputePriorityScoreAndPublish(task.getId(), previousById.get(task.getId()), task);
        }
    }

    /**
     * Persists {@code constraint} and, if it's genuinely new, increments its task's
     * {@code openConstraintCount}.
     *
     * @throws NullPointerException if constraint is null
     * @throws TaskNotFoundException if the constraint's task doesn't exist or is soft-deleted -
     * checked before writing, so a rejected call never leaves an orphaned constraint row.
     */
    @Transactional
    public void onConstraintAdded(Constraint constraint) {
        Objects.requireNonNull(constraint, "constraint must not be null");
        UUID taskId = constraint.getTaskId();
        log.info("Constraint {} added on task {}", constraint.getConstraintId(), taskId);

        requireTask(taskId);
        boolean wasInserted = constraintRepository.save(constraint);
        if (!wasInserted) {
            log.debug("Constraint {} already existed on task {} - duplicate add, no-op",
                    constraint.getConstraintId(), taskId);
            return;
        }
        updateCounters(
                taskId,
                task -> withCounters(
                        task,
                        task.getBlockingCount(),
                        task.getOpenConstraintCount() + 1));
    }

    /**
     * Transitions {@code constraintId}'s status and, if this crosses the open/closed (PENDING)
     * boundary, adjusts {@code taskId}'s {@code openConstraintCount}. Idempotent: the previous
     * status comes from the repository (the DB itself), not a caller-supplied claim.
     *
     * <p>{@code constraintId} must belong to {@code taskId}: the repository matches on both, so a
     * mismatched pair is rejected before anything is written.
     *
     * @throws NullPointerException if any argument is null
     * @throws IllegalStateException if no constraint with this id belongs to this task
     */
    @Transactional
    public void onConstraintStatusChanged(
            UUID taskId,
            String constraintId,
            Constraint.ConstraintStatus newStatus,
            Map<String, Object> reason) {

        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(constraintId, "constraintId must not be null");
        Objects.requireNonNull(newStatus, "newStatus must not be null");

        Constraint.ConstraintStatus previousStatus =
                constraintRepository.updateStatus(taskId, constraintId, newStatus, reason, LocalDateTime.now());

        boolean wasOpen = previousStatus == Constraint.ConstraintStatus.PENDING;
        boolean isOpenNow = newStatus == Constraint.ConstraintStatus.PENDING;
        if (wasOpen == isOpenNow) {
            log.debug("Constraint {} on task {} already reflects the {} -> {} transition - "
                    + "duplicate event, no-op", constraintId, taskId, previousStatus, newStatus);
            return;
        }

        int delta = isOpenNow ? 1 : -1;
        updateCounters(
                taskId,
                task -> withCounters(
                        task,
                        task.getBlockingCount(),
                        Math.max(0, task.getOpenConstraintCount() + delta)));
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

        synchronized (dependencyGraphLock) {
            AtomicReference<Task> previous = new AtomicReference<>();
            Task updated = requireUpdated(
                    taskRepository.update(taskId, task -> {
                        previous.set(task);
                        return copyOf(task).score(mindScore).confidence(confidence).build();
                    }),
                    taskId);

            recomputePriorityScoreAndPublish(taskId, previous.get(), updated);
        }
    }

    private static void requireInUnitRangeIfPresent(Double value, String name) {
        if (value != null && (value < 0.0 || value > 1.0)) {
            throw new InvalidRequestException(name + " must be between 0 and 1, got " + value);
        }
    }

    public TaskEligibilityResponse getTaskEligibility(UUID taskId) {

        Objects.requireNonNull(
                taskId,
                "taskId must not be null");

        Task task = requireTask(taskId);

        return new TaskEligibilityResponse(
                task.getId(),
                task.isReady(),
                task.getBlockingCount(),
                task.getOpenConstraintCount());
    }
}
