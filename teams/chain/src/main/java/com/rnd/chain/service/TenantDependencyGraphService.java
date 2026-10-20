package com.rnd.chain.service;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.dto.TaskReadinessSnapshot;
import com.rnd.chain.dto.TenantDependencyGraphEdge;
import com.rnd.chain.dto.TenantDependencyGraphNode;
import com.rnd.chain.dto.TenantDependencyGraphResponse;
import com.rnd.chain.repository.TaskDependencyRepository;
import com.rnd.chain.repository.TaskReadinessRepository;
import com.rnd.chain.repository.TaskRepository;
import com.rnd.chain.repository.TaskStateHistoryRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TenantDependencyGraphService {

    private final TaskRepository taskRepository;
    private final TaskDependencyRepository taskDependencyRepository;
    private final TaskReadinessRepository taskReadinessRepository;
    private final TaskStateHistoryRepository taskStateHistoryRepository;

    public TenantDependencyGraphService(
            TaskRepository taskRepository,
            TaskDependencyRepository taskDependencyRepository,
            TaskReadinessRepository taskReadinessRepository,
            TaskStateHistoryRepository taskStateHistoryRepository) {

        this.taskRepository =
                Objects.requireNonNull(
                        taskRepository,
                        "taskRepository must not be null");

        this.taskDependencyRepository =
                Objects.requireNonNull(
                        taskDependencyRepository,
                        "taskDependencyRepository must not be null");

        this.taskReadinessRepository =
                Objects.requireNonNull(
                        taskReadinessRepository,
                        "taskReadinessRepository must not be null");

        this.taskStateHistoryRepository =
                Objects.requireNonNull(
                        taskStateHistoryRepository,
                        "taskStateHistoryRepository must not be null");
    }

    @Transactional(readOnly = true)
    public TenantDependencyGraphResponse getDependencyGraph(
            UUID tenantId,
            int limit) {

        Objects.requireNonNull(
                tenantId,
                "tenantId must not be null");

        if (limit <= 0) {
            throw new IllegalArgumentException(
                    "limit must be greater than 0");
        }

        /*
         * The repository returns only non-deleted tasks
         * belonging to this tenant.
         */
        List<Task> tasks =
                taskRepository.findByTenantId(tenantId);

        if (tasks.isEmpty()) {
            return new TenantDependencyGraphResponse(
                    List.of(),
                    List.of(),
                    false);
        }

        List<UUID> taskIds =
                tasks.stream()
                        .map(Task::getId)
                        .toList();

        Set<UUID> tenantTaskIds =
                new HashSet<>(taskIds);

        /*
         * Load all dependency edges in one repository call.
         */
        List<TaskDependency> dependencies =
                taskDependencyRepository.findByTaskIds(taskIds);

        /*
         * Keep only edges whose two sides belong to this tenant.
         *
         * Cross-tenant dependencies should already be prevented by
         * business rules, but the tenant endpoint must never expose
         * tasks belonging to another tenant.
         */
        List<TaskDependency> tenantDependencies =
                dependencies.stream()
                        .filter(dependency ->
                                tenantTaskIds.contains(
                                        dependency.taskId()))
                        .filter(dependency ->
                                tenantTaskIds.contains(
                                        dependency.dependsOnTaskId()))
                        .toList();

        /*
         * task_readiness is the source of truth for readiness.
         */
        Map<UUID, TaskReadinessSnapshot> readinessByTaskId =
                taskReadinessRepository.findByIds(taskIds);

        /*
         * Latest BLOCKED history entry for each task.
         */
        Map<UUID, Instant> blockedSinceByTaskId =
                taskStateHistoryRepository
                        .findLatestBlockedSinceByTaskIds(taskIds);

        /*
         * Reverse adjacency:
         *
         * A depends on B
         *
         * B -> [A]
         *
         * This makes it possible to ask:
         * "How many tasks depend on B, directly or transitively?"
         */
        Map<UUID, List<UUID>> dependentsByTaskId =
                buildDependentsMap(tenantDependencies);

        /*
         * Calculate the transitive dependent count
         * for every task before applying the limit.
         *
         * This is important because the tasks must first
         * be ranked by their true impact.
         */
        Map<UUID, Integer> transitiveCountByTaskId =
                new HashMap<>();

        for (Task task : tasks) {
            int count =
                    countTransitiveDependents(
                            task.getId(),
                            dependentsByTaskId);

            transitiveCountByTaskId.put(
                    task.getId(),
                    count);
        }

        /*
         * Build all nodes.
         */
        List<TenantDependencyGraphNode> allNodes =
                new ArrayList<>();

        for (Task task : tasks) {

            UUID taskId =
                    task.getId();

            TaskReadinessSnapshot readiness =
                    requireReadinessSnapshot(
                            readinessByTaskId,
                            taskId);

            /*
             * blockedSince is relevant only while the task
             * is currently blocked.
             *
             * A task may have old BLOCKED entries in history
             * even though it is ready now.
             */
            Instant blockedSince =
                    readiness.isReady()
                            ? null
                            : blockedSinceByTaskId.get(taskId);

            TenantDependencyGraphNode node =
                    new TenantDependencyGraphNode(
                            taskId,
                            task.getStatus(),
                            readiness.isReady(),
                            readiness.blockingCount(),
                            transitiveCountByTaskId.get(taskId),
                            blockedSince);

            allNodes.add(node);
        }

        /*
         * Highest-impact blockers come first.
         *
         * taskId is used only as a deterministic tie-breaker
         * when two tasks have the same dependent count.
         */
        allNodes.sort(
                Comparator
                        .comparingInt(
                                TenantDependencyGraphNode::
                                        transitiveDependentsCount)
                        .reversed()
                        .thenComparing(
                                node -> node.taskId().toString()));

        boolean hasMore =
                allNodes.size() > limit;

        /*
         * Apply the requested limit only AFTER sorting.
         */
        List<TenantDependencyGraphNode> returnedNodes =
                allNodes.stream()
                        .limit(limit)
                        .toList();

        Set<UUID> returnedTaskIds =
                returnedNodes.stream()
                        .map(TenantDependencyGraphNode::taskId)
                        .collect(Collectors.toSet());

        /*
         * Return only edges where BOTH nodes survived the limit.
         */
        List<TenantDependencyGraphEdge> returnedEdges =
                tenantDependencies.stream()
                        .filter(dependency ->
                                returnedTaskIds.contains(
                                        dependency.taskId()))
                        .filter(dependency ->
                                returnedTaskIds.contains(
                                        dependency.dependsOnTaskId()))
                        .map(dependency ->
                                new TenantDependencyGraphEdge(
                                        dependency.taskId(),
                                        dependency.dependsOnTaskId()))
                        .toList();

        return new TenantDependencyGraphResponse(
                returnedNodes,
                returnedEdges,
                hasMore);
    }

    private Map<UUID, List<UUID>> buildDependentsMap(
            List<TaskDependency> dependencies) {

        Map<UUID, List<UUID>> dependentsByTaskId =
                new HashMap<>();

        for (TaskDependency dependency : dependencies) {

            dependentsByTaskId
                    .computeIfAbsent(
                            dependency.dependsOnTaskId(),
                            ignored -> new ArrayList<>())
                    .add(dependency.taskId());
        }

        return dependentsByTaskId;
    }

    private int countTransitiveDependents(
            UUID taskId,
            Map<UUID, List<UUID>> dependentsByTaskId) {

        Set<UUID> visited =
                new HashSet<>();

        Deque<UUID> stack =
                new ArrayDeque<>();

        stack.push(taskId);

        while (!stack.isEmpty()) {

            UUID current =
                    stack.pop();

            List<UUID> directDependents =
                    dependentsByTaskId.getOrDefault(
                            current,
                            List.of());

            for (UUID dependentId : directDependents) {

                /*
                 * Set prevents:
                 * 1. counting the same task twice through two paths
                 * 2. an infinite loop if corrupted data contains a cycle
                 */
                if (visited.add(dependentId)) {
                    stack.push(dependentId);
                }
            }
        }

        /*
         * Normally taskId will never be reached again because
         * cycles are forbidden. Remove defensively so a corrupted
         * graph never counts the task as its own dependent.
         */
        visited.remove(taskId);

        return visited.size();
    }

    private TaskReadinessSnapshot requireReadinessSnapshot(
            Map<UUID, TaskReadinessSnapshot> readinessByTaskId,
            UUID taskId) {

        TaskReadinessSnapshot snapshot =
                readinessByTaskId.get(taskId);

        if (snapshot == null) {
            throw new IllegalStateException(
                    "No task_readiness row found for task "
                            + taskId);
        }

        return snapshot;
    }
}