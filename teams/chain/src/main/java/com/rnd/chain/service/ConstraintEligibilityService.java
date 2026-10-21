package com.rnd.chain.service;

import com.rnd.chain.dto.Constraint;
import com.rnd.chain.dto.TaskReadinessSnapshot;
import com.rnd.chain.dto.ConstraintHistoryAction;
import com.rnd.chain.dto.ConstraintHistoryEntry;
import com.rnd.chain.exception.TaskNotFoundException;
import com.rnd.chain.repository.ConstraintHistoryRepository;
import com.rnd.chain.repository.ConstraintRepository;
import com.rnd.chain.repository.TaskReadinessRepository;
import com.rnd.chain.repository.TaskRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class ConstraintEligibilityService {

    private static final Logger log =
            LoggerFactory.getLogger(ConstraintEligibilityService.class);

    private final TaskRepository taskRepository;
    private final ConstraintRepository constraintRepository;
    private final ConstraintHistoryRepository constraintHistoryRepository;
    private final TaskCounterUpdater taskCounterUpdater;
    private final TaskReadinessRepository taskReadinessRepository;
    private final TaskStateHistoryRecorder historyRecorder;

    public ConstraintEligibilityService(
            TaskRepository taskRepository,
            ConstraintRepository constraintRepository,
            ConstraintHistoryRepository constraintHistoryRepository,
            TaskCounterUpdater taskCounterUpdater,
            TaskReadinessRepository taskReadinessRepository,
            TaskStateHistoryRecorder historyRecorder) {

        this.taskRepository = taskRepository;
        this.constraintRepository = constraintRepository;
        this.constraintHistoryRepository = constraintHistoryRepository;
        this.taskCounterUpdater = taskCounterUpdater;
        this.taskReadinessRepository = taskReadinessRepository;
        this.historyRecorder = historyRecorder;
    }

    /**
     * Persists {@code constraint} and, if it's genuinely new,
     * maintains the stored openConstraintCount.
     *
     * The authoritative readiness before/after comes from task_readiness.
     *
     * @throws NullPointerException if constraint is null
     * @throws TaskNotFoundException if the constraint's task doesn't exist
     * or is soft-deleted
     */
    @Transactional
    public void onConstraintAdded(
            Constraint constraint) {

        Objects.requireNonNull(
                constraint,
                "constraint must not be null");

        UUID taskId =
                constraint.getTaskId();

        log.info(
                "Constraint {} added on task {}",
                constraint.getConstraintId(),
                taskId);

        /*
         * Validate that the task exists and isn't soft-deleted.
         */
        taskRepository.findById(taskId)
                .orElseThrow(
                        () -> new TaskNotFoundException(taskId));

        /*
         * Must be read BEFORE writing the constraint,
         * because task_readiness derives open_constraint_count
         * directly from task_constraint.
         */
        TaskReadinessSnapshot beforeReadiness =
                requireReadinessSnapshot(taskId);

        boolean wasInserted =
                constraintRepository.save(constraint);

        if (!wasInserted) {

            log.debug(
                    "Constraint {} already existed on task {} - duplicate add, no-op",
                    constraint.getConstraintId(),
                    taskId);

            return;
        }
        recordHistory(ConstraintHistoryAction.CREATED, constraint.getConstraintId(), taskId,
                null, constraint.getStatus(), constraint.getSourceRef());

        /*
         * Constraint now exists, so the View contains
         * the authoritative new state.
         */
        TaskReadinessSnapshot afterReadiness =
                requireReadinessSnapshot(taskId);

        /*
         * Stored counter is still maintained during this migration stage.
         * Readiness/publish itself is based on before/after View snapshots.
         */
        taskCounterUpdater.updateCounters(
                taskId,
                task -> TaskCounterUpdater.withCounters(
                        task,
                        task.getBlockingCount(),
                        task.getOpenConstraintCount() + 1),
                beforeReadiness,
                afterReadiness);

        recordReadinessTransition(
                taskId,
                beforeReadiness,
                afterReadiness);
    }

    /**
     * Transitions {@code constraintId}'s status.
     *
     * If the transition crosses the PENDING/non-PENDING boundary,
     * the stored openConstraintCount is maintained, while readiness,
     * history and publishing rely on task_readiness.
     *
     * @throws NullPointerException if any argument is null
     * @throws IllegalStateException if no constraint with this id
     * belongs to this task
     */
    @Transactional
    public void onConstraintStatusChanged(
            UUID taskId,
            String constraintId,
            Constraint.ConstraintStatus newStatus,
            Map<String, Object> reason) {

        Objects.requireNonNull(
                taskId,
                "taskId must not be null");

        Objects.requireNonNull(
                constraintId,
                "constraintId must not be null");

        Objects.requireNonNull(
                newStatus,
                "newStatus must not be null");

        /*
         * Validate the task and capture the authoritative state
         * before changing the constraint.
         */
        taskRepository.findById(taskId)
                .orElseThrow(
                        () -> new TaskNotFoundException(taskId));

        TaskReadinessSnapshot beforeReadiness =
                requireReadinessSnapshot(taskId);

        // source_ref is immutable after creation, so reading it before the update is safe.
        String sourceRef = constraintRepository.findById(constraintId)
                .map(Constraint::getSourceRef)
                .orElse(null);
        Constraint.ConstraintStatus previousStatus =
                constraintRepository.updateStatus(
                        taskId,
                        constraintId,
                        newStatus,
                        reason,
                        LocalDateTime.now());

        if (previousStatus != newStatus) {
            recordHistory(ConstraintHistoryAction.STATUS_CHANGED, constraintId, taskId,
                    previousStatus, newStatus, sourceRef);
        }

        boolean wasOpen =
                previousStatus == Constraint.ConstraintStatus.PENDING;

        boolean isOpenNow =
                newStatus == Constraint.ConstraintStatus.PENDING;

        /*
         * The DB row may have changed between two non-PENDING statuses,
         * but readiness did not change, so no eligibility-counter update
         * is required.
         */
        if (wasOpen == isOpenNow) {

            log.debug(
                    "Constraint {} on task {} already reflects the {} -> {} transition - "
                            + "no readiness change",
                    constraintId,
                    taskId,
                    previousStatus,
                    newStatus);

            return;
        }

        /*
         * Read after the DB update.
         */
        TaskReadinessSnapshot afterReadiness =
                requireReadinessSnapshot(taskId);

        int delta =
                isOpenNow ? 1 : -1;

        taskCounterUpdater.updateCounters(
                taskId,
                task -> TaskCounterUpdater.withCounters(
                        task,
                        task.getBlockingCount(),
                        Math.max(
                                0,
                                task.getOpenConstraintCount() + delta)),
                beforeReadiness,
                afterReadiness);

        recordReadinessTransition(
                taskId,
                beforeReadiness,
                afterReadiness);
    }

    /**
     * TaskStateHistoryRecorder detects BLOCKED/UNBLOCKED by a zero crossing.
     *
     * Therefore the value passed here represents all readiness blockers,
     * not only dependency blockers.
     */
    private void recordReadinessTransition(
            UUID taskId,
            TaskReadinessSnapshot before,
            TaskReadinessSnapshot after) {

        historyRecorder.recordBlockingTransition(
                taskId,
                totalBlockingConditions(before),
                totalBlockingConditions(after));
    }

    private int totalBlockingConditions(
            TaskReadinessSnapshot snapshot) {

        return snapshot.blockingCount()
                + snapshot.openConstraintCount();
    }

    private TaskReadinessSnapshot requireReadinessSnapshot(
            UUID taskId) {

        TaskReadinessSnapshot snapshot =
                taskReadinessRepository
                        .findByIds(List.of(taskId))
                        .get(taskId);

        if (snapshot == null) {
            throw new IllegalStateException(
                    "No task_readiness row found for task "
                            + taskId);
        }

        return snapshot;
    }

    private void recordHistory(
            ConstraintHistoryAction action,
            String constraintId,
            UUID taskId,
            Constraint.ConstraintStatus previousStatus,
            Constraint.ConstraintStatus newStatus,
            String sourceRef) {

        constraintHistoryRepository.record(
                new ConstraintHistoryEntry(
                        null,
                        constraintId,
                        taskId,
                        action,
                        previousStatus,
                        newStatus,
                        sourceRef,
                        Instant.now()));
    }
}

