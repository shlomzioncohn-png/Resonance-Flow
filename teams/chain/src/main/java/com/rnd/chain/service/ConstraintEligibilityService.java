package com.rnd.chain.service;

import com.rnd.chain.dto.Constraint;
import com.rnd.chain.exception.TaskNotFoundException;
import com.rnd.chain.repository.ConstraintRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class ConstraintEligibilityService {

    private static final Logger log =
            LoggerFactory.getLogger(ConstraintEligibilityService.class);

    private final ConstraintRepository constraintRepository;
    private final TaskCounterUpdater taskCounterUpdater;

    public ConstraintEligibilityService(
            ConstraintRepository constraintRepository,
            TaskCounterUpdater taskCounterUpdater) {

        this.constraintRepository = constraintRepository;
        this.taskCounterUpdater = taskCounterUpdater;
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

        taskCounterUpdater.requireTask(taskId);
        boolean wasInserted = constraintRepository.save(constraint);
        if (!wasInserted) {
            log.debug("Constraint {} already existed on task {} - duplicate add, no-op",
                    constraint.getConstraintId(), taskId);
            return;
        }
        taskCounterUpdater.updateCounters(
                taskId,
                task -> TaskCounterUpdater.withCounters(
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
        taskCounterUpdater.updateCounters(
                taskId,
                task -> TaskCounterUpdater.withCounters(
                        task,
                        task.getBlockingCount(),
                        Math.max(0, task.getOpenConstraintCount() + delta)));
    }
}
