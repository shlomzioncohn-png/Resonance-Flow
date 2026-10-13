package com.rnd.chain.service;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.exception.DependencyNotFoundException;
import com.rnd.chain.exception.DuplicateDependencyException;
import com.rnd.chain.exception.InvalidDependencyException;
import com.rnd.chain.repository.TaskDependencyRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class DependencyEdgeService {

    private static final Logger log =
            LoggerFactory.getLogger(DependencyEdgeService.class);

    private final TaskDependencyRepository taskDependencyRepository;
    private final DependencyGraphLock dependencyGraphLock;
    private final TaskCounterUpdater taskCounterUpdater;

    public DependencyEdgeService(
            TaskDependencyRepository taskDependencyRepository,
            DependencyGraphLock dependencyGraphLock,
            TaskCounterUpdater taskCounterUpdater) {

        this.taskDependencyRepository = taskDependencyRepository;
        this.dependencyGraphLock = dependencyGraphLock;
        this.taskCounterUpdater = taskCounterUpdater;
    }

    public TaskDependency addDependency(
            UUID taskId,
            UUID dependsOnTaskId) {

        Objects.requireNonNull(
                taskId,
                "taskId must not be null");

        Objects.requireNonNull(
                dependsOnTaskId,
                "dependsOnTaskId must not be null");

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

            Task task = taskCounterUpdater.requireTask(taskId);
            Task dependencyTask = taskCounterUpdater.requireTask(dependsOnTaskId);

            Optional<TaskDependency> saved =
                    taskDependencyRepository.save(
                            new TaskDependency(
                                    taskId,
                                    dependsOnTaskId));

            TaskDependency dependency = saved.orElseThrow(
                    () -> new DuplicateDependencyException(
                            "Task "
                                    + taskId
                                    + " already depends on "
                                    + dependsOnTaskId));

            boolean dependencyAlreadyDone =
                    dependencyTask.getStatus() == TaskStatus.DONE;

            taskCounterUpdater.adjustBlockingCount(
                    task.getId(),
                    dependencyAlreadyDone,
                    1);

            return dependency;
        }
    }

    private record PendingEdge(UUID dependsOnTaskId, boolean isNewEdge, boolean dependsOnAlreadyDone) {
    }

    /** Cycle/existence checks only - no writes. An edge that already exists is a redelivery and
     * comes back as a no-op ({@code isNewEdge == false}), not an error. */
    private PendingEdge validateEdge(UUID taskId, UUID dependsOnTaskId) {
        if (taskDependencyRepository.existsDependency(taskId, dependsOnTaskId)) {
            return new PendingEdge(dependsOnTaskId, false, false);
        }
        if (wouldCreateCycle(taskId, dependsOnTaskId)) {
            throw new InvalidDependencyException(
                    "Dependency would create a cycle: " + taskId + " -> " + dependsOnTaskId);
        }
        taskCounterUpdater.requireTask(taskId);
        boolean dependsOnAlreadyDone =
                taskCounterUpdater.requireTask(dependsOnTaskId).getStatus() == TaskStatus.DONE;
        return new PendingEdge(dependsOnTaskId, true, dependsOnAlreadyDone);
    }

    /** Writes one already-validated edge - no cycle/existence checks, see {@link #validateEdge}. */
    private void applyEdge(UUID taskId, PendingEdge edge) {
        if (!edge.isNewEdge()) {
            log.info("Dependency {} -> {} already existed, blockingCount left unchanged",
                    taskId, edge.dependsOnTaskId());
            return;
        }
        taskDependencyRepository.save(new TaskDependency(taskId, edge.dependsOnTaskId()));
        taskCounterUpdater.adjustBlockingCount(taskId, edge.dependsOnAlreadyDone(), 1);
    }

    /**
     * Same effect as calling {@link #addDependency} once per id, but validates every entry
     * against the current graph state before applying any of them, so one invalid entry (a
     * cycle, or a dependency target Chain hasn't seen yet) fails the whole batch with zero
     * partial effect (PR #467 review, on the Kafka handler that batches a task.dependencies.proposed
     * event's array through this method). Entries that already exist are no-ops, so a redelivered
     * event is safe to apply again.
     *
     * <p>Validating up front is safe because {@link #wouldCreateCycle} only traverses edges that
     * already exist before this call, and every same-batch edge starts at {@code taskId}. The whole
     * batch runs under one {@code dependencyGraphLock} acquisition, so no concurrent writer can
     * invalidate a validated entry before it is applied.
     */
    @Transactional
    public void addDependencies(UUID taskId, List<UUID> dependsOnTaskIds) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(dependsOnTaskIds, "dependsOnTaskIds must not be null");
        List<UUID> distinctIds = dependsOnTaskIds.stream()
                .peek(id -> Objects.requireNonNull(id, "dependsOnTaskIds must not contain null elements"))
                .distinct()
                .toList();
        log.info("Adding {} distinct dependency(ies) for task {}", distinctIds.size(), taskId);

        synchronized (dependencyGraphLock) {
            List<PendingEdge> validated = new ArrayList<>(distinctIds.size());
            for (UUID dependsOnTaskId : distinctIds) {
                validated.add(validateEdge(taskId, dependsOnTaskId));
            }
            for (PendingEdge edge : validated) {
                applyEdge(taskId, edge);
            }
        }
    }

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

            taskCounterUpdater.requireTask(taskId);

            Task dependencyTask = taskCounterUpdater.requireTask(dependsOnTaskId);

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

            taskCounterUpdater.adjustBlockingCount(
                    taskId,
                    dependencyCurrentlyDone,
                    -1);
        }
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
}
