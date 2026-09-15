package com.rnd.chain.service;

import com.rnd.chain.dto.DependencyChangeAction;
import com.rnd.chain.dto.DependencyChangeSource;
import com.rnd.chain.dto.DependencyHistoryEntry;
import com.rnd.chain.dto.PageResponse;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.exception.DependencyNotFoundException;
import com.rnd.chain.exception.DuplicateDependencyException;
import com.rnd.chain.exception.InvalidDependencyException;
import com.rnd.chain.exception.InvalidRequestException;
import com.rnd.chain.exception.TaskNotFoundException;
import com.rnd.chain.repository.DependencyHistoryRepository;
import com.rnd.chain.repository.TaskDependencyRepository;
import com.rnd.chain.repository.TaskRepository;
import com.rnd.chain.repository.TransactionRunner;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
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
 * <p><b>Known limitation, out of scope for this class:</b>
 * {@link #addDependency}/{@link #removeDependency} don't update
 * {@code Task.blockingCount}/{@code isReady} - keeping those in sync is
 * left to whichever service owns task-readiness/eligibility.
 */
@Service
public class TaskDependencyService {

    private final TaskRepository taskRepository;
    private final TaskDependencyRepository taskDependencyRepository;
    private final DependencyHistoryRepository historyRepository;
    private final TransactionRunner transactionRunner;
    private final Clock clock;

    public TaskDependencyService(TaskRepository taskRepository,
                                  TaskDependencyRepository taskDependencyRepository,
                                  DependencyHistoryRepository historyRepository,
                                  TransactionRunner transactionRunner,
                                  Clock clock) {
        Objects.requireNonNull(taskRepository, "taskRepository must not be null");
        Objects.requireNonNull(taskDependencyRepository, "taskDependencyRepository must not be null");
        Objects.requireNonNull(historyRepository, "historyRepository must not be null");
        Objects.requireNonNull(transactionRunner, "transactionRunner must not be null");
        Objects.requireNonNull(clock, "clock must not be null");
        this.taskRepository = taskRepository;
        this.taskDependencyRepository = taskDependencyRepository;
        this.historyRepository = historyRepository;
        this.transactionRunner = transactionRunner;
        this.clock = clock;
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
            TaskDependency saved = taskDependencyRepository.save(new TaskDependency(taskId, dependsOnTaskId))
                    .orElseThrow(() -> new DuplicateDependencyException(
                            "Task " + taskId + " already depends on " + dependsOnTaskId));
            // Safe today: no I/O, and the transaction lock rules out any interleaving write -
            // this compensating write cannot fail or no-op. Revisit once a real DB replaces this repository.
            recordHistoryOrCompensate(taskId, dependsOnTaskId, DependencyChangeAction.ADDED, source, sourceRef,
                    () -> taskDependencyRepository.delete(taskId, dependsOnTaskId));
            return saved;
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

        if (isMatchingRetry(taskId, dependsOnTaskId, DependencyChangeAction.REMOVED, source, sourceRef)) {
            return;
        }

        transactionRunner.runInTransaction(() -> {
            if (isMatchingRetry(taskId, dependsOnTaskId, DependencyChangeAction.REMOVED, source, sourceRef)) {
                return null;
            }
            boolean removed = taskDependencyRepository.delete(taskId, dependsOnTaskId);
            if (!removed) {
                throw new DependencyNotFoundException(
                        "No dependency from " + taskId + " to " + dependsOnTaskId);
            }
            recordHistoryOrCompensate(taskId, dependsOnTaskId, DependencyChangeAction.REMOVED, source, sourceRef,
                    () -> taskDependencyRepository.save(new TaskDependency(taskId, dependsOnTaskId)));
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

        // Reads go through the same transaction lock as writes so they can never
        // observe a state mid-rollback (see addDependency/removeDependency).
        return transactionRunner.runInTransaction(() -> taskDependencyRepository.findByTaskId(taskId).stream()
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

        return transactionRunner.runInTransaction(() -> taskDependencyRepository.findByDependsOnTaskId(taskId).stream()
                .map(TaskDependency::taskId)
                .toList());
    }

    /** Empty page (not 404) if the task exists but has no history yet. */
    public PageResponse<DependencyHistoryEntry> getHistory(UUID taskId, int page, int size) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        requireTaskExists(taskId);

        return transactionRunner.runInTransaction(() -> historyRepository.findByTaskId(taskId, page, size));
    }

    /** Records the history entry; on failure runs {@code compensate} to undo the edge write, then rethrows. */
    private void recordHistoryOrCompensate(UUID taskId, UUID dependsOnTaskId, DependencyChangeAction action,
                                            DependencyChangeSource source, String sourceRef, Runnable compensate) {
        try {
            recordHistory(taskId, dependsOnTaskId, action, source, sourceRef);
        } catch (RuntimeException historyWriteFailed) {
            compensate.run();
            throw historyWriteFailed;
        }
    }

    private void recordHistory(UUID taskId, UUID dependsOnTaskId, DependencyChangeAction action,
                                DependencyChangeSource source, String sourceRef) {
        historyRepository.record(DependencyHistoryEntry.builder()
                .id(UUID.randomUUID())
                .taskId(taskId)
                .dependsOnTaskId(dependsOnTaskId)
                .action(action)
                .source(source)
                .sourceRef(sourceRef)
                .createdAt(LocalDateTime.now(clock))
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
        boolean matches = entry.getAction() == action
                && entry.getTaskId().equals(taskId)
                && entry.getDependsOnTaskId().equals(dependsOnTaskId);
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
