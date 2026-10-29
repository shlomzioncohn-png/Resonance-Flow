package com.rnd.chain.service;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.dto.TaskReadinessSnapshot;
import com.rnd.chain.exception.CrossTenantDependencyException;
import com.rnd.chain.exception.DependencyNotFoundException;
import com.rnd.chain.exception.DuplicateDependencyException;
import com.rnd.chain.exception.InvalidDependencyException;
import com.rnd.chain.exception.TaskNotFoundException;
import com.rnd.chain.repository.GraphLock;
import com.rnd.chain.repository.TaskDependencyRepository;
import com.rnd.chain.repository.TaskReadinessRepository;
import com.rnd.chain.repository.TaskRepository;

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

    private final TaskRepository taskRepository;
    private final TaskDependencyRepository taskDependencyRepository;
    private final GraphLock graphLock;
    private final PriorityScoreService priorityScoreService;
    private final TaskStateHistoryRecorder historyRecorder;
    private final TaskReadinessRepository taskReadinessRepository;

    public DependencyEdgeService(
            TaskRepository taskRepository,
            TaskDependencyRepository taskDependencyRepository,
            GraphLock graphLock,
            PriorityScoreService priorityScoreService,
            TaskStateHistoryRecorder historyRecorder,
            TaskReadinessRepository taskReadinessRepository) {

        this.taskRepository = taskRepository;
        this.taskDependencyRepository = taskDependencyRepository;
        this.graphLock = graphLock;
        this.priorityScoreService = priorityScoreService;
        this.historyRecorder = historyRecorder;
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

    @Transactional
    public void insertPlaceholderIfAbsent(
            UUID taskId,
            UUID tenantId) {

        taskRepository.insertIfAbsent(
                Task.placeholder(
                        taskId,
                        tenantId));
    }

    /**
     * History/readiness/publish decisions are based on task_readiness.
     */
    private void recomputeAndRecord(
            UUID taskId,
            TaskReadinessSnapshot beforeReadiness,
            TaskReadinessSnapshot afterReadiness) {

        recomputePriority(
                taskId,
                beforeReadiness,
                afterReadiness);

        historyRecorder.recordBlockingTransition(
                taskId,
                 beforeReadiness.blockingCount()
                + beforeReadiness.openConstraintCount(),
                afterReadiness.blockingCount()
                + afterReadiness.openConstraintCount());
    }

    /**
     * Same as {@link #addDependency(UUID, UUID, Double)},
     * with no Mind confidence to carry.
     */
    @Transactional
    public TaskDependency addDependency(
            UUID taskId,
            UUID dependsOnTaskId) {

        return addDependency(
                taskId,
                dependsOnTaskId,
                1.0);
    }

    /**
     * Same as {@link #addDependency(UUID, UUID)},
     * with explicit Mind confidence.
     */
    @Transactional
    public TaskDependency addDependency(
            UUID taskId,
            UUID dependsOnTaskId,
            Double confidence) {


        return doAddDependency(taskId, dependsOnTaskId, confidence, null);

    }

    /**
     * Same as addDependency, but missing tasks can be created
     * as placeholders in the given tenant.
     */
    @Transactional
    public TaskDependency addDependencyCreatingMissing(
            UUID taskId,
            UUID dependsOnTaskId,
            Double confidence,
            UUID tenantId) {


        Objects.requireNonNull(tenantId, "tenantId must not be null");
        return doAddDependency(taskId, dependsOnTaskId, confidence, tenantId);

    }

    /** A non-null {@code tenantId} means missing tasks are created as placeholders in that tenant. */
    private TaskDependency doAddDependency(
            UUID taskId,
            UUID dependsOnTaskId,
            Double confidence,
            UUID tenantId) {

        Objects.requireNonNull(
                taskId,
                "taskId must not be null");

        Objects.requireNonNull(
                dependsOnTaskId,
                "dependsOnTaskId must not be null");

        if (confidence != null
                && (confidence < 0.0 || confidence > 1.0)) {

            throw new InvalidDependencyException(
                    "confidence for dependency "
                            + taskId
                            + " -> "
                            + dependsOnTaskId
                            + " must be between 0 and 1, got "
                            + confidence);
        }
        UUID lockTenantId =
            tenantId != null
                ? tenantId
                : tenantIdForTask(taskId);
        return graphLock.runExclusive(lockTenantId, () -> {


            if (tenantId != null) {
                insertPlaceholdersIfAbsent(taskId, dependsOnTaskId, tenantId);

            }

            /*
             * Duplicate is checked before task/cycle lookup:
             * an existing edge cannot introduce a new cycle.
             */
            if (taskDependencyRepository.existsDependency(
                    taskId,
                    dependsOnTaskId)) {

                throw new DuplicateDependencyException(
                        "Task "
                                + taskId
                                + " already depends on "
                                + dependsOnTaskId);
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


            Task task = taskRepository.findById(taskId)
                    .orElseThrow(
                            () -> new TaskNotFoundException(taskId));
            Task dependencyTask = taskRepository.findById(dependsOnTaskId)
                    .orElseThrow(
                            () -> new TaskNotFoundException(dependsOnTaskId));
            if (tenantId != null) {
                requireTenant(task, tenantId);
                requireTenant(dependencyTask, tenantId);
            } else {
                requireSameTenant(task, dependencyTask);

            }

            /*
             * IMPORTANT:
             * Must be captured BEFORE inserting the edge,
             * because task_readiness derives blocking_count
             * directly from task_dependency.
             */
            TaskReadinessSnapshot beforeReadiness =
                    requireReadinessSnapshot(taskId);

            Optional<TaskDependency> saved =
                    taskDependencyRepository.save(
                            new TaskDependency(
                                    taskId,
                                    dependsOnTaskId,
                                    confidence));

            TaskDependency dependency =
                    saved.orElseThrow(
                            () -> new DuplicateDependencyException(
                                    "Task "
                                            + taskId
                                            + " already depends on "
                                            + dependsOnTaskId));

            /*
             * The edge now exists, so the View represents
             * the new authoritative state.
             */
            TaskReadinessSnapshot afterReadiness =
                    requireReadinessSnapshot(taskId);

            recomputeAndRecord(
                    task.getId(),
                    beforeReadiness,
                    afterReadiness);

            recomputeDependencyTargetPriority(
                    dependsOnTaskId);

            return dependency;
        });
    }

    /**
     * The dependency target's own blocking state doesn't change,
     * but its dependent-confidence sum can change its priority.
     *
     * Readiness snapshots are identical because this operation
     * does not change the target's own readiness.
     */
    private void recomputeDependencyTargetPriority(
            UUID dependsOnTaskId) {

        TaskReadinessSnapshot readiness =
                requireReadinessSnapshot(dependsOnTaskId);

        recomputePriority(
                dependsOnTaskId,
                readiness,
                readiness);
    }

    private record PendingEdge(
            UUID dependsOnTaskId,
            Double confidence,
            boolean isNewEdge) {
    }

    /**
     * Cycle/existence checks only - no writes.
     */
    private PendingEdge validateEdge(
            UUID taskId,
            UUID dependsOnTaskId,
            Double confidence) {

        if (taskDependencyRepository.existsDependency(
                taskId,
                dependsOnTaskId)) {

            return new PendingEdge(
                    dependsOnTaskId,
                    confidence,
                    false);
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

        Task task =
                taskRepository.findById(taskId)
                    .orElseThrow(
                            () -> new TaskNotFoundException(taskId));

        Task dependsOnTask =
                taskRepository.findById(dependsOnTaskId)
                    .orElseThrow(
                            () -> new TaskNotFoundException(dependsOnTaskId));

        requireSameTenant(
                task,
                dependsOnTask);

        return new PendingEdge(
                dependsOnTaskId,
                confidence,
                true);
    }

    private static void requireTenant(
            Task task,
            UUID tenantId) {

        if (!tenantId.equals(task.getTenantId())) {

            throw new CrossTenantDependencyException(
                    "Dependency rejected: task "
                            + task.getId()
                            + " (tenant "
                            + task.getTenantId()
                            + ") is not in the event's tenant "
                            + tenantId);
        }
    }

    /**
     * Rejects an edge between tasks which do not share
     * the same confirmed tenant.
     */
    private static void requireSameTenant(
            Task task,
            Task dependsOnTask) {

        UUID taskTenantId =
                task.getTenantId();

        UUID dependsOnTenantId =
                dependsOnTask.getTenantId();

        if (taskTenantId == null
                || dependsOnTenantId == null
                || !taskTenantId.equals(dependsOnTenantId)) {

            throw new CrossTenantDependencyException(
                    "Dependency rejected: task "
                            + task.getId()
                            + " (tenant "
                            + taskTenantId
                            + ") and task "
                            + dependsOnTask.getId()
                            + " (tenant "
                            + dependsOnTenantId
                            + ") do not share a confirmed tenant");
        }
    }

    /**
     * Writes one already-validated edge.
     */
    private void applyEdge(
            UUID taskId,
            PendingEdge edge) {

        /*
         * Capture authoritative readiness BEFORE changing/upserting the edge.
         */
        TaskReadinessSnapshot beforeReadiness =
                requireReadinessSnapshot(taskId);

        /*
         * Save also performs confidence upsert for an existing edge.
         */
        taskDependencyRepository.save(
                new TaskDependency(
                        taskId,
                        edge.dependsOnTaskId(),
                        edge.confidence()));

        TaskReadinessSnapshot afterReadiness =
                requireReadinessSnapshot(taskId);

        if (!edge.isNewEdge()) {

            log.info(
                    "Dependency {} -> {} already existed, "
                            + "confidence updated to {}",
                    taskId,
                    edge.dependsOnTaskId(),
                    edge.confidence());

            /*
             * Redelivery does not change readiness,
             * but confidence may change priorityScore.
             */
            recomputePriority(
                    taskId,
                    beforeReadiness,
                    afterReadiness);

            recomputeDependencyTargetPriority(
                    edge.dependsOnTaskId());

            return;
        }

        recomputeAndRecord(
                taskId,
                beforeReadiness,
                afterReadiness);

        recomputeDependencyTargetPriority(
                edge.dependsOnTaskId());
    }

    /**
     * Same as addDependencies(Map), with default confidence 1.0.
     */
    @Transactional
    public void addDependencies(
            UUID taskId,
            List<UUID> dependsOnTaskIds) {

        Objects.requireNonNull(
                dependsOnTaskIds,
                "dependsOnTaskIds must not be null");

        Map<UUID, Double> withDefaultConfidence =
                new LinkedHashMap<>();

        for (UUID id : dependsOnTaskIds) {

            Objects.requireNonNull(
                    id,
                    "dependsOnTaskIds must not contain null elements");

            withDefaultConfidence.put(
                    id,
                    1.0);
        }

        addDependencies(
                taskId,
                withDefaultConfidence);
    }

    /**
     * Validates all edges before applying any of them.
     */
    @Transactional
    public void addDependencies(
            UUID taskId,
            Map<UUID, Double> dependsOnTaskIdsWithConfidence) {

        Objects.requireNonNull(
                taskId,
                "taskId must not be null");

        Objects.requireNonNull(
                dependsOnTaskIdsWithConfidence,
                "dependsOnTaskIdsWithConfidence must not be null");

        dependsOnTaskIdsWithConfidence.forEach(
                (id, confidence) -> {

                    Objects.requireNonNull(
                            id,
                            "dependsOnTaskIdsWithConfidence must not contain a null id");

                    if (confidence == null
                            || confidence < 0.0
                            || confidence > 1.0) {

                        throw new InvalidDependencyException(
                                "confidence for dependency "
                                        + taskId
                                        + " -> "
                                        + id
                                        + " must be between 0 and 1, got "
                                        + confidence);
                    }
                });

        log.info(
                "Adding {} distinct dependency(ies) for task {}",
                dependsOnTaskIdsWithConfidence.size(),
                taskId);
        UUID lockTenantId = tenantIdForTask(taskId);
       graphLock.runExclusive(lockTenantId, () -> {

            List<PendingEdge> validated =
                    new ArrayList<>(
                            dependsOnTaskIdsWithConfidence.size());

            for (Map.Entry<UUID, Double> entry :
                    dependsOnTaskIdsWithConfidence.entrySet()) {

                validated.add(
                        validateEdge(
                                taskId,
                                entry.getKey(),
                                entry.getValue()));
            }

            for (PendingEdge edge : validated) {
                applyEdge(
                        taskId,
                        edge);
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

        UUID lockTenantId = tenantIdForTask(taskId);

        graphLock.runExclusive(lockTenantId, () -> {
            taskRepository.findById(taskId)
                    .orElseThrow(
                            () -> new TaskNotFoundException(taskId));

            taskRepository.findById(dependsOnTaskId)
                    .orElseThrow(
                            () -> new TaskNotFoundException(dependsOnTaskId));

            /*
             * Capture BEFORE while the edge still exists.
             */
            TaskReadinessSnapshot beforeReadiness =
                    requireReadinessSnapshot(taskId);

            boolean removed =
                    taskDependencyRepository.delete(
                            taskId,
                            dependsOnTaskId);

            if (!removed) {

                throw new DependencyNotFoundException(
                        "No dependency from "
                                + taskId
                                + " to "
                                + dependsOnTaskId);
            }

            /*
             * Now the View contains the state without the edge.
             */
            TaskReadinessSnapshot afterReadiness =
                    requireReadinessSnapshot(taskId);

            recomputeAndRecord(
                    taskId,
                    beforeReadiness,
                    afterReadiness);

            recomputeDependencyTargetPriority(
                    dependsOnTaskId);
        });
    }

    private boolean wouldCreateCycle(
            UUID taskId,
            UUID dependsOnTaskId) {

        Set<UUID> visited =
                new HashSet<>();

        Deque<UUID> toVisit =
                new ArrayDeque<>();

        toVisit.push(
                dependsOnTaskId);

        while (!toVisit.isEmpty()) {

            UUID current =
                    toVisit.pop();

            if (current.equals(taskId)) {
                return true;
            }

            if (!visited.add(current)) {
                continue;
            }

            /*
             * Under graph lock, so repository-level read lock
             * is unnecessary here.
             */
            for (TaskDependency dependency :
                    taskDependencyRepository
                            .findByTaskIdUnderExternalLock(current)) {

                toVisit.push(
                        dependency.dependsOnTaskId());
            }
        }

        return false;
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

    /**
     * Recomputes the task's priority score and queues a scheduling publish when the
     * authoritative before/after View snapshots (or the score) changed. The task row is
     * locked for the duration, as the old counter update did.
     */
    @Transactional
    public Task recomputePriority(
            UUID taskId,
            TaskReadinessSnapshot beforeReadiness,
            TaskReadinessSnapshot afterReadiness) {

        Objects.requireNonNull(
                beforeReadiness,
                "beforeReadiness must not be null");

        Objects.requireNonNull(
                afterReadiness,
                "afterReadiness must not be null");

        Task locked =
                taskRepository.update(
                                taskId,
                                UnaryOperator.identity())
                        .orElseThrow(
                                () -> new TaskNotFoundException(taskId));

        return priorityScoreService.recomputePriorityScoreAndPublish(
                taskId,
                locked,
                locked,
                beforeReadiness,
                afterReadiness);
    }
    private UUID tenantIdForTask(UUID taskId) {
        TaskReadinessSnapshot snapshot =
            taskReadinessRepository
                    .findByIds(List.of(taskId))
                    .get(taskId);

        return snapshot != null
            ? snapshot.tenantId()
            : null;
}
}
