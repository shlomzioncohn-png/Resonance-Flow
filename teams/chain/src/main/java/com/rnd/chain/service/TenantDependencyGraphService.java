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

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

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
import java.util.BitSet;

@Service
@RequiredArgsConstructor
public class TenantDependencyGraphService {

    @NonNull
    private final TaskRepository taskRepository;
    @NonNull
    private final TaskDependencyRepository taskDependencyRepository;
    @NonNull
    private final TaskReadinessRepository taskReadinessRepository;
    @NonNull
    private final TaskStateHistoryRepository taskStateHistoryRepository;


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
            calculateTransitiveDependentCounts(
                    tasks,
                    dependentsByTaskId);

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
    private Map<UUID, Integer> calculateTransitiveDependentCounts(
        List<Task> tasks,
        Map<UUID, List<UUID>> dependentsByTaskId) {

        int taskCount = tasks.size();

        Map<UUID, Integer> indexByTaskId =
            new HashMap<>(taskCount);

        for (int i = 0; i < taskCount; i++) {
            indexByTaskId.put(
                tasks.get(i).getId(),
                i);
        }

        int[] indegree =
            new int[taskCount];

        for (Map.Entry<UUID, List<UUID>> entry
            : dependentsByTaskId.entrySet()) {

            for (UUID dependentId : entry.getValue()) {

                Integer dependentIndex =
                    indexByTaskId.get(dependentId);

                if (dependentIndex != null) {
                indegree[dependentIndex]++;
                }
            }
        }

        Deque<UUID> queue =
            new ArrayDeque<>();

        for (Task task : tasks) {

            Integer index =
                indexByTaskId.get(task.getId());

            if (indegree[index] == 0) {
                queue.addLast(task.getId());
            }
        }

        List<UUID> topologicalOrder =
            new ArrayList<>(taskCount);

        while (!queue.isEmpty()) {

            UUID current =
                queue.removeFirst();

            topologicalOrder.add(current);

            for (UUID dependentId
                : dependentsByTaskId.getOrDefault(
                        current,
                        List.of())) {

                Integer dependentIndex =
                    indexByTaskId.get(dependentId);

                if (dependentIndex == null) {
                continue;
                }

                indegree[dependentIndex]--;

                if (indegree[dependentIndex] == 0) {
                queue.addLast(dependentId);
                }
            }
        }

    /*
     * Dependency graphs are expected to be acyclic.
     * Fall back to the defensive DFS implementation if corrupted
     * data contains a cycle.
     */
        if (topologicalOrder.size() != taskCount) {

            Map<UUID, Integer> fallbackCounts =
                new HashMap<>(taskCount);

            for (Task task : tasks) {
                fallbackCounts.put(
                    task.getId(),
                    countTransitiveDependents(
                            task.getId(),
                            dependentsByTaskId));
        }

            return fallbackCounts;
        }

        BitSet[] transitiveDependents =
            new BitSet[taskCount];

        for (int i = 0; i < taskCount; i++) {
            transitiveDependents[i] =
                new BitSet(taskCount);
        }

        Map<UUID, Integer> counts =
            new HashMap<>(taskCount);

    /*
     * Reverse topological order guarantees that every direct
     * dependent has already calculated its own transitive dependents.
     */
        for (int i = topologicalOrder.size() - 1;
            i >= 0;
            i--) {

            UUID taskId =
                topologicalOrder.get(i);

            int taskIndex =
                indexByTaskId.get(taskId);

            BitSet reachable =
                transitiveDependents[taskIndex];

            for (UUID dependentId
                : dependentsByTaskId.getOrDefault(
                        taskId,
                        List.of())) {

                Integer dependentIndex =
                    indexByTaskId.get(dependentId);

                if (dependentIndex == null) {
                continue;
                }

                reachable.set(dependentIndex);

                reachable.or(
                    transitiveDependents[
                            dependentIndex]);
            }

        /*
         * Defensive only: a valid DAG can never reach itself.
         */
            reachable.clear(taskIndex);

            counts.put(
                taskId,
                reachable.cardinality());
        }

        return counts;
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