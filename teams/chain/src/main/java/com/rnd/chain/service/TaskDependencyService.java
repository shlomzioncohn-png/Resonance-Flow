package com.rnd.chain.service;

import com.rnd.chain.dto.DependencyChangeAction;
import com.rnd.chain.dto.DependencyChangeSource;
import com.rnd.chain.dto.DependencyHistoryEntry;
import com.rnd.chain.dto.PageResponse;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.exception.CrossTenantDependencyException;
import com.rnd.chain.exception.InvalidDependencyException;
import com.rnd.chain.exception.InvalidRequestException;
import com.rnd.chain.exception.TaskNotFoundException;
import com.rnd.chain.repository.DependencyHistoryRepository;
import com.rnd.chain.repository.TaskDependencyRepository;
import com.rnd.chain.repository.TaskRepository;
import com.rnd.chain.repository.TransactionRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Enforces the business rules around the dependency graph on top of the
 * plain-CRUD {@link TaskDependencyRepository}: both tasks must exist, a
 * task cannot depend on itself, and a given edge cannot be added twice.
 * Also records an audit trail entry for every successful add/remove.
 *
 * Dependency mutations delegate to TaskEligibilityService to keep readiness in sync.
 */
@Service
public class TaskDependencyService {

    private static final Logger log = LoggerFactory.getLogger(TaskDependencyService.class);

    private final TaskRepository taskRepository;
    private final TaskDependencyRepository taskDependencyRepository;
    private final DependencyHistoryRepository historyRepository;
    private final TransactionRunner transactionRunner;
    private final Clock clock;
    private final TaskEligibilityService taskEligibilityService;

    public TaskDependencyService(TaskRepository taskRepository,
                                  TaskDependencyRepository taskDependencyRepository,
                                  DependencyHistoryRepository historyRepository,
                                  TransactionRunner transactionRunner,
                                  Clock clock,
                                  TaskEligibilityService taskEligibilityService) {
        Objects.requireNonNull(taskRepository, "taskRepository must not be null");
        Objects.requireNonNull(taskDependencyRepository, "taskDependencyRepository must not be null");
        Objects.requireNonNull(historyRepository, "historyRepository must not be null");
        Objects.requireNonNull(transactionRunner, "transactionRunner must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        Objects.requireNonNull(taskEligibilityService, "taskEligibilityService must not be null");
        this.taskRepository = taskRepository;
        this.taskDependencyRepository = taskDependencyRepository;
        this.historyRepository = historyRepository;
        this.transactionRunner = transactionRunner;
        this.clock = clock;
        this.taskEligibilityService = taskEligibilityService;
    }

    /** Same as {@link #addDependency(UUID, UUID, DependencyChangeSource, String)} with source USER, no sourceRef. */
    public TaskDependency addDependency(UUID taskId, UUID dependsOnTaskId) {
        return addDependency(taskId, dependsOnTaskId, DependencyChangeSource.USER, null);
    }

    /**
     * Creates a "taskId depends on dependsOnTaskId" edge and records a
     * history entry for it in the same transaction (see
     * {@link TransactionRunner}). {@code sourceRef} is required for
     * {@code MIND}/{@code SYSTEM} and is the idempotency key: a repeat
     * call with the same (source, sourceRef, taskId, dependsOnTaskId) short-
     * circuits and returns the original result instead of throwing on the
     * now-existing edge. Reusing a (source, sourceRef) for a *different*
     * task pair is treated as a caller bug, not a retry - see
     * {@link #isMatchingRetry}.
     *
     * <p><b>Known assumption, not fixed here:</b> {@link #requireTaskExists}
     * runs before the atomic {@code save}, but task existence is checked
     * against a different repository ({@link TaskRepository}) than the one
     * the edge is saved to. Tracked as issue #243.
     *
     * @see <a href="https://github.com/KamaTechOrg/Resonance-Flow/issues/243">#243</a>
     * @throws InvalidRequestException if sourceRef is missing for MIND/SYSTEM,
     * or was already used for a different taskId/dependsOnTaskId/action
     * @throws InvalidDependencyException  if taskId equals dependsOnTaskId
     * @throws TaskNotFoundException       if either task does not exist
     * @throws DuplicateDependencyException if the edge already exists
     */
    public TaskDependency addDependency(UUID taskId, UUID dependsOnTaskId,
                                         DependencyChangeSource source, String sourceRef) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(dependsOnTaskId, "dependsOnTaskId must not be null");
        Objects.requireNonNull(source, "source must not be null");
        requireSourceRefIfNeeded(source, sourceRef);


        if (taskId.equals(dependsOnTaskId)) {
            throw new InvalidDependencyException(
                    "Task " + taskId + " cannot depend on itself");
        }


        // Existence is checked before the idempotency short-circuit (unlike before) so a
        // retry for a task that was deleted since the original call fails loudly instead
        // of reporting a fabricated success.
        requireTaskExists(taskId);
        requireTaskExists(dependsOnTaskId);

        if (isMatchingRetry(taskId, dependsOnTaskId, DependencyChangeAction.ADDED, source, sourceRef)) {
            return new TaskDependency(taskId, dependsOnTaskId);
        }

        return transactionRunner.runInTransaction(() -> {
            // Re-checked under the transaction lock: the outer check above is only a fast
            // path and can race with a concurrent identical retry (see stage-3 concurrency test).
            if (isMatchingRetry(taskId, dependsOnTaskId, DependencyChangeAction.ADDED, source, sourceRef)) {
                return new TaskDependency(taskId, dependsOnTaskId);
            }
            // Record history before delegating graph and readiness mutation; remove the
            // speculative entry if the delegate rejects the change.
            DependencyHistoryEntry recorded = recordHistory(taskId, dependsOnTaskId,
                    DependencyChangeAction.ADDED, source, sourceRef);
            try {
                return taskEligibilityService.addDependency(taskId, dependsOnTaskId);
            } catch (CrossTenantDependencyException failure) {
                // Issue #580: unlike a duplicate/missing-task no-op (compensateHistory alone),
                // this rejection must stay visible, not just vanish with the speculative entry -
                // replace it with a REJECTED one instead of merely deleting it. Only attempt that
                // if the delete itself succeeded (compensateHistory returns the same failure
                // instance on success, a different one if the delete itself failed) - recording
                // REJECTED on top of a still-dangling speculative ADDED entry would just mask
                // whatever that deeper problem was.
                RuntimeException toThrow = compensateHistory(recorded.id(), failure);
                if (toThrow == failure) {
                    recordHistory(taskId, dependsOnTaskId, DependencyChangeAction.REJECTED, source, sourceRef);
                }
                throw toThrow;
            } catch (RuntimeException failure) {
                throw compensateHistory(recorded.id(), failure);
            }
        });
    }

    /** Same as {@link #removeDependency(UUID, UUID, DependencyChangeSource, String)} with source USER, no sourceRef. */
    public void removeDependency(UUID taskId, UUID dependsOnTaskId) {
        removeDependency(taskId, dependsOnTaskId, DependencyChangeSource.USER, null);
    }

    /**
     * Removes the "taskId depends on dependsOnTaskId" edge and records a
     * history entry for it in the same transaction. Same idempotency
     * contract as {@link #addDependency(UUID, UUID, DependencyChangeSource, String)}.
     *
     * @throws InvalidRequestException if sourceRef is missing for MIND/SYSTEM,
     * or was already used for a different taskId/dependsOnTaskId/action
     * @throws DependencyNotFoundException if no such edge exists
     */
    public void removeDependency(UUID taskId, UUID dependsOnTaskId,
                                  DependencyChangeSource source, String sourceRef) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(dependsOnTaskId, "dependsOnTaskId must not be null");
        Objects.requireNonNull(source, "source must not be null");
        requireSourceRefIfNeeded(source, sourceRef);
        requireTaskExists(taskId);
        requireTaskExists(dependsOnTaskId);

        if (isMatchingRetry(taskId, dependsOnTaskId, DependencyChangeAction.REMOVED, source, sourceRef)) {
            return;
        }

        transactionRunner.runInTransaction(() -> {
            if (isMatchingRetry(taskId, dependsOnTaskId, DependencyChangeAction.REMOVED, source, sourceRef)) {
                return null;
            }
            // Record history before the delegated removal and compensate on failure.
            DependencyHistoryEntry recorded = recordHistory(taskId, dependsOnTaskId,
                    DependencyChangeAction.REMOVED, source, sourceRef);
            try {
                taskEligibilityService.removeDependency(taskId, dependsOnTaskId);
            } catch (RuntimeException failure) {
                throw compensateHistory(recorded.id(), failure);
            }
            return null;
        });
    }

    /**
     * @return the ids of the tasks that the given task directly depends
     * on (what blocks it). Empty list if it has none.
     * @throws TaskNotFoundException if taskId does not exist
     */
    public List<UUID> getDependencies(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        requireTaskExists(taskId);

        // Read-only: waits for an in-flight write (so it can never observe a state mid-rollback,
        // see addDependency/removeDependency) but runs concurrently with other reads.
        return transactionRunner.runInReadOnlyTransaction(() -> taskDependencyRepository.findByTaskId(taskId).stream()
                .map(TaskDependency::dependsOnTaskId)
                .toList());
    }

    /**
     * @return the ids of the tasks that directly depend on the given
     * task (what it blocks). Empty list if nothing depends on it.
     * @throws TaskNotFoundException if taskId does not exist
     */
    public List<UUID> getDependents(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        requireTaskExists(taskId);

        return transactionRunner.runInReadOnlyTransaction(() -> taskDependencyRepository.findByDependsOnTaskId(taskId).stream()
                .map(TaskDependency::taskId)
                .toList());
    }

    /**
     * Empty page (not 404) if the task exists but has no history yet.
     *
     * @throws InvalidRequestException if page is negative, or size is not in 1..100
     */
    public PageResponse<DependencyHistoryEntry> getHistory(UUID taskId, int page, int size) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        if (page < 0 || size <= 0 || size > 100) {
            throw new InvalidRequestException("page must be >= 0 and size must be in 1..100");
        }
        requireTaskExists(taskId);

        return transactionRunner.runInReadOnlyTransaction(() -> historyRepository.findByTaskId(taskId, page, size));
    }

    /**
     * Deletes the speculatively-recorded history entry that turned out not to match an actual
     * graph change, and returns {@code original} to throw. If the deletion itself fails, that
     * failure - not {@code original} - is what gets returned (with {@code original} attached via
     * {@link Throwable#addSuppressed}) and logged at error level: it means the graph and the
     * history log may now disagree, which is the more urgent problem to surface.
     */
    private RuntimeException compensateHistory(UUID entryId, RuntimeException original) {
        try {
            historyRepository.delete(entryId);
            return original;
        } catch (RuntimeException compensationFailed) {
            compensationFailed.addSuppressed(original);
            log.error("Failed to compensate history entry {} after a no-op graph write - "
                    + "graph and history may now disagree", entryId, compensationFailed);
            return compensationFailed;
        }
    }

    private DependencyHistoryEntry recordHistory(UUID taskId, UUID dependsOnTaskId, DependencyChangeAction action,
                                                  DependencyChangeSource source, String sourceRef) {
        return historyRepository.record(DependencyHistoryEntry.builder()
                .id(UUID.randomUUID())
                .taskId(taskId)
                .dependsOnTaskId(dependsOnTaskId)
                .action(action)
                .source(source)
                .sourceRef(sourceRef)
                .createdAt(Instant.now(clock))
                .build());
    }

    /**
     * true if (source, sourceRef) already has a recorded entry matching this exact
     * (taskId, dependsOnTaskId, action) - a legitimate retry, safe to short-circuit.
     * Always false if sourceRef is null (no idempotency key to look up).
     *
     * @throws InvalidRequestException if (source, sourceRef) was already recorded
     * for a *different* taskId/dependsOnTaskId/action - reusing an idempotency key
     * for a different operation is a caller bug, not a retry, so it must not silently
     * report success for whatever the caller passed this time.
     */
    private boolean isMatchingRetry(UUID taskId, UUID dependsOnTaskId, DependencyChangeAction action,
                                     DependencyChangeSource source, String sourceRef) {
        if (sourceRef == null) {
            return false;
        }
        Optional<DependencyHistoryEntry> existing = historyRepository.findBySourceAndSourceRef(source, sourceRef);
        if (existing.isEmpty()) {
            return false;
        }
        DependencyHistoryEntry entry = existing.get();
        boolean matches = entry.action() == action
                && entry.taskId().equals(taskId)
                && entry.dependsOnTaskId().equals(dependsOnTaskId);
        if (!matches) {
            throw new InvalidRequestException("sourceRef " + sourceRef + " for source " + source
                    + " was already used for a different dependency change");
        }
        return true;
    }

    /** MIND/SYSTEM are automated, retry-exposed sources - sourceRef is their idempotency key, so it's mandatory. */
    private static void requireSourceRefIfNeeded(DependencyChangeSource source, String sourceRef) {
        boolean sourceRefRequired = source == DependencyChangeSource.MIND || source == DependencyChangeSource.SYSTEM;
        if (sourceRefRequired && (sourceRef == null || sourceRef.isBlank())) {
            throw new InvalidRequestException("sourceRef is required for source " + source);
        }
    }

    private void requireTaskExists(UUID taskId) {
        taskRepository.findById(taskId)
                .orElseThrow(() -> new TaskNotFoundException("Task not found: " + taskId));
    }
}
