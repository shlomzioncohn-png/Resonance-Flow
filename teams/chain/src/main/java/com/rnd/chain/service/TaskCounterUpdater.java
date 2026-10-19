package com.rnd.chain.service;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.dto.TaskReadinessSnapshot;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.events.SchedulingPublishListener;
import com.rnd.chain.exception.InvalidRequestException;
import com.rnd.chain.exception.TaskNotFoundException;
import com.rnd.chain.repository.TaskDependencyRepository;
import com.rnd.chain.repository.TaskReadinessRepository;
import com.rnd.chain.repository.TaskRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
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

    /**
     * TransactionSynchronizationManager resource key for the per-transaction
     * set of taskIds queued to publish at commit.
     */
    private static final String PENDING_PUBLISH_RESOURCE_KEY =
            TaskCounterUpdater.class.getName()
                    + ".pendingSchedulingPublishes";

    private final TaskRepository taskRepository;
    private final TaskDependencyRepository taskDependencyRepository;
    private final PriorityScoreService priorityScoreService;
    private final SchedulingPublishListener schedulingPublishListener;
    private final TaskReadinessRepository taskReadinessRepository;

    public TaskCounterUpdater(
            TaskRepository taskRepository,
            TaskDependencyRepository taskDependencyRepository,
            PriorityScoreService priorityScoreService,
            SchedulingPublishListener schedulingPublishListener,
            TaskReadinessRepository taskReadinessRepository) {

        this.taskRepository = taskRepository;
        this.taskDependencyRepository = taskDependencyRepository;
        this.priorityScoreService = priorityScoreService;
        this.schedulingPublishListener = schedulingPublishListener;
        this.taskReadinessRepository = taskReadinessRepository;
    }

    /**
     * Creates a PENDING placeholder with epoch updatedAt for each missing task.
     * Existing tasks are never modified.
     */
    @Transactional
    public void insertPlaceholdersIfAbsent(
            UUID taskId,
            UUID dependsOnTaskId,
            UUID tenantId) {

        insertPlaceholderIfAbsent(
                taskId,
                tenantId);

        insertPlaceholderIfAbsent(
                dependsOnTaskId,
                tenantId);
    }

    private void insertPlaceholderIfAbsent(
            UUID taskId,
            UUID tenantId) {

        taskRepository.insertIfAbsent(
                Task.builder()
                        .id(taskId)
                        .tenantId(tenantId)
                        .status(TaskStatus.PENDING)
                        .isReady(true)
                        .createdAt(LocalDateTime.now())
                        .updatedAt(
                                LocalDateTime.ofEpochSecond(
                                        0,
                                        0,
                                        ZoneOffset.UTC))
                        .build());
    }

    public Task requireTask(UUID taskId) {

        return taskRepository.findById(taskId)
                .orElseThrow(
                        () -> new TaskNotFoundException(taskId));
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

    /**
     * Stored counters are still maintained during this migration stage.
     *
     * Readiness decisions must not rely on the isReady value produced here.
     * task_readiness is the authoritative source for such decisions.
     */
    public static Task withCounters(
            Task task,
            int blockingCount,
            int openConstraintCount) {

        boolean isReady =
                blockingCount == 0
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

    /**
     * Compatibility overload for callers that only mutate the stored counters.
     *
     * The View state does not change as a result of changing the stored
     * compatibility columns themselves, so the same authoritative View
     * snapshot is used before and after.
     */
    @Transactional
    public Task updateCounters(
            UUID taskId,
            UnaryOperator<Task> countersMutator) {

        TaskReadinessSnapshot readiness =
                requireReadinessSnapshot(taskId);

        return updateCounters(
                taskId,
                countersMutator,
                readiness,
                readiness);
    }

    /**
     * Compatibility overload for maintaining the stored blocking counter.
     *
     * Decisions about readiness/publishing are still made from task_readiness.
     */
    @Transactional
    public void adjustBlockingCount(
            UUID taskId,
            boolean dependsOnIsDone,
            int delta) {

        TaskReadinessSnapshot readiness =
                requireReadinessSnapshot(taskId);

        adjustBlockingCount(
                taskId,
                dependsOnIsDone,
                delta,
                readiness,
                readiness);
    }

    /**
     * Recomputes priorityScore using task_readiness as the authoritative
     * source for openConstraintCount.
     *
     * Publishing is based on the View readiness transition, never on
     * Task.isReady().
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
                priorityScoreService.compute(
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
                    requireUpdated(
                            taskRepository.update(
                                    taskId,
                                    task -> copyOf(task)
                                            .priorityScore(recomputed)
                                            .build()),
                            taskId);
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

                requestPublishAfterCommit(taskId);
            }
        }

        return finalTask;
    }

    /**
     * Compatibility overload.
     *
     * It is safe only for mutations that do not themselves change the
     * task_readiness View. Readiness and openConstraintCount still come from
     * the View rather than the stored Task columns.
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

    /**
     * Queues one scheduling update per taskId per transaction.
     *
     * Publishing occurs only after a successful commit.
     */
    private void requestPublishAfterCommit(
            UUID taskId) {

        if (!TransactionSynchronizationManager
                .isSynchronizationActive()) {

            log.error(
                    "requestPublishAfterCommit({}) called with no active "
                            + "transaction - publish dropped",
                    taskId);

            return;
        }

        @SuppressWarnings("unchecked")
        Set<UUID> pending =
                (Set<UUID>)
                        TransactionSynchronizationManager
                                .getResource(
                                        PENDING_PUBLISH_RESOURCE_KEY);

        if (pending == null) {

            pending =
                    new LinkedHashSet<>();

            TransactionSynchronizationManager
                    .bindResource(
                            PENDING_PUBLISH_RESOURCE_KEY,
                            pending);
        }

        if (!pending.add(taskId)) {
            return;
        }

        if (pending.size() == 1) {

            TransactionSynchronizationManager
                    .registerSynchronization(
                            new TransactionSynchronization() {

                                @Override
                                public void afterCommit() {

                                    @SuppressWarnings("unchecked")
                                    Set<UUID> idsToPublish =
                                            (Set<UUID>)
                                                    TransactionSynchronizationManager
                                                            .getResource(
                                                                    PENDING_PUBLISH_RESOURCE_KEY);

                                    for (UUID id :
                                            idsToPublish) {

                                        schedulingPublishListener
                                                .onCommit(id);
                                    }
                                }

                                @Override
                                public void afterCompletion(
                                        int status) {

                                    TransactionSynchronizationManager
                                            .unbindResourceIfPossible(
                                                    PENDING_PUBLISH_RESOURCE_KEY);
                                }
                            });
        }
    }

    /**
     * Applies a Mind score/confidence update.
     *
     * Priority recomputation uses openConstraintCount from task_readiness,
     * not from the stored Task columns.
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
                requireUpdated(
                        taskRepository.update(
                                taskId,
                                task -> {

                                    previous.set(task);

                                    return copyOf(task)
                                            .score(mindScore)
                                            .confidence(confidence)
                                            .build();
                                }),
                        taskId);

        recomputePriorityScoreAndPublish(
                taskId,
                previous.get(),
                updated,
                readiness,
                readiness);
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

    /**
     * Updates the stored compatibility counters while using authoritative
     * before/after View snapshots for all readiness and publish decisions.
     */
    @Transactional
    public Task updateCounters(
            UUID taskId,
            UnaryOperator<Task> countersMutator,
            TaskReadinessSnapshot beforeReadiness,
            TaskReadinessSnapshot afterReadiness) {

        Objects.requireNonNull(
                beforeReadiness,
                "beforeReadiness must not be null");

        Objects.requireNonNull(
                afterReadiness,
                "afterReadiness must not be null");

        AtomicReference<Task> previous =
                new AtomicReference<>();

        Task updated =
                requireUpdated(
                        taskRepository.update(
                                taskId,
                                task -> {

                                    previous.set(task);

                                    return countersMutator.apply(
                                            task);
                                }),
                        taskId);

        Task oldTask =
                previous.get();

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

        return recomputePriorityScoreAndPublish(
                taskId,
                oldTask,
                updated,
                beforeReadiness,
                afterReadiness);
    }

    /**
     * Maintains the stored blocking counter while readiness/publishing is
     * decided exclusively from the supplied View snapshots.
     */
    @Transactional
    public void adjustBlockingCount(
            UUID taskId,
            boolean dependsOnIsDone,
            int delta,
            TaskReadinessSnapshot beforeReadiness,
            TaskReadinessSnapshot afterReadiness) {

        int effectiveDelta =
                dependsOnIsDone
                        ? 0
                        : delta;

        updateCounters(
                taskId,
                task -> withCounters(
                        task,
                        Math.max(
                                0,
                                task.getBlockingCount()
                                        + effectiveDelta),
                        task.getOpenConstraintCount()),
                beforeReadiness,
                afterReadiness);
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