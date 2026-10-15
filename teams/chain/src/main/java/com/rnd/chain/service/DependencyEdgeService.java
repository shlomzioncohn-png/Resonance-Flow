package com.rnd.chain.service;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.exception.CrossTenantDependencyException;
import com.rnd.chain.exception.DependencyNotFoundException;
import com.rnd.chain.exception.DuplicateDependencyException;
import com.rnd.chain.exception.InvalidDependencyException;
import com.rnd.chain.repository.GraphLock;
import com.rnd.chain.repository.TaskDependencyRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

@Service
public class DependencyEdgeService {

    private static final Logger log =
            LoggerFactory.getLogger(DependencyEdgeService.class);

    private final TaskDependencyRepository taskDependencyRepository;
    private final GraphLock graphLock;
    private final TaskCounterUpdater taskCounterUpdater;

    public DependencyEdgeService(
            TaskDependencyRepository taskDependencyRepository,
            GraphLock graphLock,
            TaskCounterUpdater taskCounterUpdater) {

        this.taskDependencyRepository = taskDependencyRepository;
        this.graphLock = graphLock;
        this.taskCounterUpdater = taskCounterUpdater;
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
     * for this specific edge instead of defaulting to full confidence.
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

        return graphLock.runExclusive(() -> {

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
            requireSameTenant(task, dependencyTask);

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

            taskCounterUpdater.adjustBlockingCount(
                    task.getId(),
                    dependencyAlreadyDone,
                    1);
            recomputeDependencyTargetPriority(dependsOnTaskId);

            return dependency;
        });
    }

    /**
     * Issue #445: {@code dependsOnTaskId} just gained (or lost, or had its confidence changed on)
     * a dependent - its own {@code blockingCount}/{@code openConstraintCount} are untouched, but
     * its dependent-confidence sum changed, which can move its priorityScore. A no-op counters
     * mutator routes it through the same {@link TaskCounterUpdater#recomputePriorityScoreAndPublish}
     * every other change goes through, rather than duplicating that decision here.
     */
    private void recomputeDependencyTargetPriority(UUID dependsOnTaskId) {
        taskCounterUpdater.updateCounters(dependsOnTaskId, UnaryOperator.identity());
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
        Task task = taskCounterUpdater.requireTask(taskId);
        Task dependsOnTask = taskCounterUpdater.requireTask(dependsOnTaskId);
        requireSameTenant(task, dependsOnTask);
        boolean dependsOnAlreadyDone = dependsOnTask.getStatus() == TaskStatus.DONE;
        return new PendingEdge(dependsOnTaskId, confidence, true, dependsOnAlreadyDone);
    }

    /**
     * Rejects an edge between two tasks that don't share a confirmed tenant - either their
     * {@code tenantId}s differ, or either one is still null. A null tenant is treated as
     * "not yet known", not "any tenant" (issue #580's strict policy): existing/test data without
     * a tenant is not meaningful enough to warrant the weaker "skip the check" alternative.
     */
    private static void requireSameTenant(Task task, Task dependsOnTask) {
        UUID taskTenantId = task.getTenantId();
        UUID dependsOnTenantId = dependsOnTask.getTenantId();
        if (taskTenantId == null || dependsOnTenantId == null || !taskTenantId.equals(dependsOnTenantId)) {
            throw new CrossTenantDependencyException(
                    "Dependency rejected: task " + task.getId() + " (tenant " + taskTenantId
                            + ") and task " + dependsOnTask.getId() + " (tenant " + dependsOnTenantId
                            + ") do not share a confirmed tenant");
        }
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
            // ends' priority_score - recompute both without touching any counters.
            taskCounterUpdater.updateCounters(taskId, UnaryOperator.identity());
            recomputeDependencyTargetPriority(edge.dependsOnTaskId());
            return;
        }
        taskCounterUpdater.adjustBlockingCount(taskId, edge.dependsOnAlreadyDone(), 1);
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
     * batch runs under one graph-lock acquisition, so no concurrent writer can
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

        graphLock.runExclusive(() -> {
            List<PendingEdge> validated = new ArrayList<>(dependsOnTaskIdsWithConfidence.size());
            for (Map.Entry<UUID, Double> entry : dependsOnTaskIdsWithConfidence.entrySet()) {
                validated.add(validateEdge(taskId, entry.getKey(), entry.getValue()));
            }
            for (PendingEdge edge : validated) {
                applyEdge(taskId, edge);
            }
        });
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

        graphLock.runExclusive(() -> {

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
            recomputeDependencyTargetPriority(dependsOnTaskId);
        });
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

            // Under the graph lock, so the repository's own read lock is redundant here.
            for (TaskDependency dependency :
                    taskDependencyRepository.findByTaskIdUnderExternalLock(current)) {

                toVisit.push(
                        dependency.dependsOnTaskId());
            }
        }

        return false;
    }
}
