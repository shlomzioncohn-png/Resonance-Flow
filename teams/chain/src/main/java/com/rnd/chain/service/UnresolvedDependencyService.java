
package com.rnd.chain.service;

import com.rnd.chain.dto.DependencyChangeSource;
import com.rnd.chain.dto.UnresolvedDependency;
import com.rnd.chain.exception.DuplicateDependencyException;
import com.rnd.chain.repository.UnresolvedDependencyRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class UnresolvedDependencyService {

    private static final Logger log =
            LoggerFactory.getLogger(
                    UnresolvedDependencyService.class);

    public static final String TARGET_TASK_NOT_FOUND =
            "TARGET_TASK_NOT_FOUND";

    private final UnresolvedDependencyRepository repository;
    private final TaskDependencyService taskDependencyService;
    private final Clock clock;

    public UnresolvedDependencyService(
            UnresolvedDependencyRepository repository,
            TaskDependencyService taskDependencyService,
            Clock clock) {

        this.repository =
                Objects.requireNonNull(
                        repository,
                        "repository must not be null");

        this.taskDependencyService =
                Objects.requireNonNull(
                        taskDependencyService,
                        "taskDependencyService must not be null");

        this.clock =
                Objects.requireNonNull(
                        clock,
                        "clock must not be null");
    }

    public UnresolvedDependency recordMissingTarget(
            UUID taskId,
            UUID dependsOnTaskId,
            DependencyChangeSource source,
            String sourceRef) {

        Objects.requireNonNull(
                taskId,
                "taskId must not be null");

        Objects.requireNonNull(
                dependsOnTaskId,
                "dependsOnTaskId must not be null");

        Objects.requireNonNull(
                source,
                "source must not be null");

        Objects.requireNonNull(
                sourceRef,
                "sourceRef must not be null");

        Optional<UnresolvedDependency> existing =
                repository.findByTaskIdAndDependsOnTaskId(
                        taskId,
                        dependsOnTaskId);

        if (existing.isPresent()) {

            log.info(
                    "Unresolved dependency [{} -> {}] already exists",
                    taskId,
                    dependsOnTaskId);

            return existing.get();
        }

        UnresolvedDependency unresolved =
                new UnresolvedDependency(
                        UUID.randomUUID(),
                        taskId,
                        dependsOnTaskId,
                        source,
                        sourceRef,
                        TARGET_TASK_NOT_FOUND,
                        Instant.now(clock));

        UnresolvedDependency saved =
                repository.save(unresolved);

        log.info(
                "Recorded unresolved dependency [{} -> {}]",
                taskId,
                dependsOnTaskId);

        return saved;
    }

    public List<UnresolvedDependency> findWaitingForTask(
            UUID dependsOnTaskId) {

        Objects.requireNonNull(
                dependsOnTaskId,
                "dependsOnTaskId must not be null");

        return repository.findByDependsOnTaskId(
                dependsOnTaskId);
    }

    /**
     * Called when a task that may have previously been missing
     * becomes available.
     *
     * Finds every dependency waiting for this task and retries it.
     */
    public void resolveWaitingForTask(
            UUID newlyAvailableTaskId) {

        Objects.requireNonNull(
                newlyAvailableTaskId,
                "newlyAvailableTaskId must not be null");

        List<UnresolvedDependency> waiting =
                repository.findByDependsOnTaskId(
                        newlyAvailableTaskId);

        if (waiting.isEmpty()) {

            log.info(
                    "No unresolved dependencies waiting for task [{}]",
                    newlyAvailableTaskId);

            return;
        }

        log.info(
                "Found [{}] unresolved dependencies waiting for task [{}]",
                waiting.size(),
                newlyAvailableTaskId);

        for (UnresolvedDependency unresolved : waiting) {

            resolveOne(unresolved);
        }
    }

    private void resolveOne(
            UnresolvedDependency unresolved) {

        try {

            taskDependencyService.addDependency(
                    unresolved.taskId(),
                    unresolved.dependsOnTaskId(),
                    unresolved.source(),
                    unresolved.sourceRef());

            repository.deleteById(
                    unresolved.id());

            log.info(
                    "Resolved dependency [{} -> {}]",
                    unresolved.taskId(),
                    unresolved.dependsOnTaskId());

        } catch (DuplicateDependencyException ex) {

            /*
             * The dependency already exists.
             * Therefore the unresolved record is no longer needed.
             */
            repository.deleteById(
                    unresolved.id());

            log.info(
                    "Dependency [{} -> {}] already exists. "
                            + "Removing stale unresolved record",
                    unresolved.taskId(),
                    unresolved.dependsOnTaskId());
        }
    }

    public void remove(UUID id) {

        Objects.requireNonNull(
                id,
                "id must not be null");

        repository.deleteById(id);
    }
}