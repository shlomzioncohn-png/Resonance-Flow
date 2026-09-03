package com.rnd.chain.service;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.dto.TaskResponse;
import com.rnd.chain.exception.TaskNotFoundException;
import com.rnd.chain.repository.TaskDependencyRepository;
import com.rnd.chain.repository.TaskRepository;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Graph traversal over the Chain domain: same-tier dependency traversal
 * (direct/transitive dependencies and dependents) and parent/child
 * hierarchy traversal (ancestors/children/descendants), plus a cross-tier
 * trace combining both axes.
 *
 * Every traversal is an iterative, visited-set-guarded BFS (ArrayDeque as
 * the queue, HashSet<UUID> as the visited set), matching the style of
 * TaskEligibilityService.wouldCreateCycle: the visited set does double
 * duty as cycle-guard and dedup, so multiple paths to the same node never
 * produce duplicates and cyclic/invalid data can never loop forever - a
 * defensive guard, even though cycles are already prevented at write time
 * elsewhere. No maxDepth parameter anywhere: the visited set is the real
 * safety mechanism.
 *
 * Builds no persistent graph-wide index of its own: each node's repository
 * lookup happens exactly once, naturally, via the visited set (a node is
 * never expanded twice within one traversal call).
 */
public class TaskTraversalService {

    private final TaskRepository taskRepository;
    private final TaskDependencyRepository taskDependencyRepository;

    public TaskTraversalService(TaskRepository taskRepository, TaskDependencyRepository taskDependencyRepository) {
        this.taskRepository = taskRepository;
        this.taskDependencyRepository = taskDependencyRepository;
    }

    /**
     * @return the direct dependencies of {@code taskId} (tasks it directly depends on).
     */
    public List<TaskResponse> getDirectDependencies(UUID taskId) {
        requireTask(taskId);
        return toResponses(collectReachableIds(taskId, this::dependencyNeighborIds, true));
    }

    /**
     * @return every transitive dependency of {@code taskId} (its direct dependencies, their
     * dependencies, and so on), nearest-first, each node appearing at most once.
     */
    public List<TaskResponse> getAllDependencies(UUID taskId) {
        requireTask(taskId);
        return toResponses(collectReachableIds(taskId, this::dependencyNeighborIds, false));
    }

    /**
     * @return the direct dependents of {@code taskId} (tasks that directly depend on it).
     */
    public List<TaskResponse> getDirectDependents(UUID taskId) {
        requireTask(taskId);
        return toResponses(collectReachableIds(taskId, this::dependentNeighborIds, true));
    }

    /**
     * @return every transitive dependent of {@code taskId} (its direct dependents, their
     * dependents, and so on), nearest-first, each node appearing at most once.
     */
    public List<TaskResponse> getAllDependents(UUID taskId) {
        requireTask(taskId);
        return toResponses(collectReachableIds(taskId, this::dependentNeighborIds, false));
    }

    /**
     * @return the direct children of {@code taskId} in the hierarchy.
     */
    public List<TaskResponse> getDirectChildren(UUID taskId) {
        requireTask(taskId);
        return toResponses(collectReachableIds(taskId, this::childNeighborIds, true));
    }

    /**
     * @return every descendant of {@code taskId} in the hierarchy (its direct children, their
     * children, and so on), nearest-first, each node appearing at most once.
     */
    public List<TaskResponse> getAllDescendants(UUID taskId) {
        requireTask(taskId);
        return toResponses(collectReachableIds(taskId, this::childNeighborIds, false));
    }

    /**
     * @return the ancestor chain of {@code taskId}, nearest parent first and the root last.
     * There is no separate "direct ancestor" method - the first entry of this list already is
     * the direct parent. Unlike the dependency/hierarchy-descendant traversals above, this
     * walks a single parentTaskId chain rather than a general graph, so it isn't built on
     * collectReachableIds - it has its own visited set as a defensive cycle guard instead, since
     * the hierarchy is supposed to be a tree (a task is never its own ancestor).
     */
    public List<TaskResponse> getAncestors(UUID taskId) {
        Task start = requireTask(taskId);
        return toResponses(ancestorIds(start));
    }

    private List<UUID> ancestorIds(Task start) {
        List<UUID> chain = new ArrayList<>();
        Set<UUID> visited = new HashSet<>();
        visited.add(start.getId());
        UUID parentId = start.getParentTaskId();
        while (parentId != null) {
            if (!visited.add(parentId)) {
                // Defensive cycle guard: the hierarchy is supposed to be a tree, so this should
                // never trigger in practice, but stop rather than loop forever if it ever does.
                break;
            }
            Optional<Task> parent = taskRepository.findById(parentId);
            if (!parent.isPresent()) {
                // Dangling parent reference - stop rather than fail the whole traversal.
                break;
            }
            chain.add(parentId);
            parentId = parent.get().getParentTaskId();
        }
        return chain;
    }

    private List<UUID> dependencyNeighborIds(UUID taskId) {
        List<UUID> ids = new ArrayList<>();
        for (TaskDependency dependency : taskDependencyRepository.findByTaskId(taskId)) {
            ids.add(dependency.getDependsOnTaskId());
        }
        return ids;
    }

    private List<UUID> dependentNeighborIds(UUID taskId) {
        List<UUID> ids = new ArrayList<>();
        for (TaskDependency dependency : taskDependencyRepository.findByDependsOnTaskId(taskId)) {
            ids.add(dependency.getTaskId());
        }
        return ids;
    }

    private List<UUID> childNeighborIds(UUID taskId) {
        List<UUID> ids = new ArrayList<>();
        for (Task child : taskRepository.findByParentTaskId(taskId)) {
            ids.add(child.getId());
        }
        return ids;
    }

    /**
     * Resolves {@code taskId} through the repository, throwing {@link TaskNotFoundException} if
     * it's missing or soft-deleted. Checked once per public method call, not once per node
     * visited during the traversal itself.
     */
    private Task requireTask(UUID taskId) {
        return taskRepository.findById(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));
    }

    /**
     * Iterative BFS from {@code startTaskId} following {@code neighborFn}. With
     * {@code directOnly=true}, only the start node's own first-level neighbors are collected
     * (the BFS stops right after processing the start node, before expanding anything further).
     * With {@code directOnly=false}, the BFS runs to completion, collecting every node
     * transitively reachable from the start node.
     *
     * The visited set (seeded with the start node itself, so it's never included in its own
     * result) guarantees each node appears at most once in the result regardless of how many
     * paths reach it, and guarantees termination even over cyclic/invalid data, since a node
     * already visited is never re-expanded. As a direct consequence, {@code neighborFn} is
     * called at most once per unique node reachable from the start node.
     */
    private List<UUID> collectReachableIds(UUID startTaskId, Function<UUID, List<UUID>> neighborFn, boolean directOnly) {
        List<UUID> result = new ArrayList<>();
        Set<UUID> visited = new HashSet<>();
        visited.add(startTaskId);
        Deque<UUID> queue = new ArrayDeque<>();
        queue.add(startTaskId);
        while (!queue.isEmpty()) {
            UUID current = queue.poll();
            for (UUID neighbor : neighborFn.apply(current)) {
                if (visited.add(neighbor)) {
                    result.add(neighbor);
                    queue.add(neighbor);
                }
            }
            if (directOnly) {
                // Only the start node's own neighbors are wanted - the queue may still hold
                // items added just above, but nothing more is ever polled from it.
                break;
            }
        }
        return result;
    }

    /**
     * Converts task ids to {@link TaskResponse}s, in the given order. An id that no longer
     * resolves to a task (missing, or soft-deleted since the edge/parent-reference was created)
     * is silently skipped rather than failing the whole traversal - a dangling reference inside
     * the graph is a traversal-safety case, not an error.
     */
    private List<TaskResponse> toResponses(List<UUID> ids) {
        List<TaskResponse> result = new ArrayList<>();
        for (UUID id : ids) {
            taskRepository.findById(id).ifPresent(task -> result.add(TaskResponse.from(task)));
        }
        return result;
    }
}
