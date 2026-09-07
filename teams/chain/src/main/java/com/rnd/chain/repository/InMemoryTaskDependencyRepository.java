package com.rnd.chain.repository;

import com.rnd.chain.dto.TaskDependency;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory implementation of {@link TaskDependencyRepository}, indexed by task id in both
 * directions (see code review finding: "TaskEligibilityService cycle-check is O(V*E) instead
 * of O(V+E), held under the global lock").
 *
 * <p>The previous implementation stored edges as one synchronized flat list, so both
 * findByTaskId and findByDependsOnTaskId were a full O(E) linear scan under a lock - and
 * since TaskEligibilityService.wouldCreateCycle's DFS calls findByTaskId once per visited
 * node, one cycle check cost O(V*E) total, all of it held under dependencyGraphLock (a
 * de facto single-writer bottleneck that gets worse as the graph grows). save() also did its
 * own separate O(E) scan (isSameEdge/anyMatch) to check for a duplicate edge, on top of
 * whatever the caller already checked via edgeExists().
 *
 * <p>Two {@link ConcurrentHashMap}&lt;UUID, Set&lt;UUID&gt;&gt; adjacency maps mirror each
 * other: {@code dependenciesByTask} (taskId -&gt; the set of ids it depends on) and
 * {@code dependentsByTask} (dependsOnTaskId -&gt; the set of ids that depend on it). Both
 * findByTaskId and findByDependsOnTaskId are now O(1) average plus O(k) to materialize the
 * result, where k is that one task's out-/in-degree - not the whole graph's edge count. save()
 * relies on {@link Set#add} to tell whether an edge was already present, so it no longer needs
 * a separate duplicate-check scan (or the isSameEdge helper the old linear-scan version needed
 * to reimplement TaskDependency.equals() for - a Set already uses equals()/hashCode() itself).
 */
public class InMemoryTaskDependencyRepository implements TaskDependencyRepository {

    private final Map<UUID, Set<UUID>> dependenciesByTask = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> dependentsByTask = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();

    public InMemoryTaskDependencyRepository(List<TaskDependency> edges) {
        Objects.requireNonNull(edges, "edges must not be null");
        synchronized (writeLock) {
            for (TaskDependency edge : edges) {
                indexEdge(edge.getTaskId(), edge.getDependsOnTaskId());
            }
        }
    }

    @Override
    public List<TaskDependency> findByTaskId(UUID taskId) {
        return dependenciesByTask.getOrDefault(taskId, Set.of()).stream()
                .map(dependsOnTaskId -> new TaskDependency(taskId, dependsOnTaskId))
                .collect(Collectors.toList());
    }

    @Override
    public List<TaskDependency> findByDependsOnTaskId(UUID taskId) {
        return dependentsByTask.getOrDefault(taskId, Set.of()).stream()
                .map(dependentTaskId -> new TaskDependency(dependentTaskId, taskId))
                .collect(Collectors.toList());
    }

    @Override
    public TaskDependency save(TaskDependency dependency) {
        Objects.requireNonNull(dependency, "dependency must not be null");
        synchronized (writeLock) {
            indexEdge(dependency.getTaskId(), dependency.getDependsOnTaskId());
        }
        return dependency;
    }

    @Override
    public void delete(UUID taskId, UUID dependsOnTaskId) {
        synchronized (writeLock) {
            Set<UUID> forward = dependenciesByTask.get(taskId);
            if (forward != null) {
                forward.remove(dependsOnTaskId);
                if (forward.isEmpty()) {
                    dependenciesByTask.remove(taskId);
                }
            }
            Set<UUID> reverse = dependentsByTask.get(dependsOnTaskId);
            if (reverse != null) {
                reverse.remove(taskId);
                if (reverse.isEmpty()) {
                    dependentsByTask.remove(dependsOnTaskId);
                }
            }
        }
    }

    /** Must run under {@link #writeLock}: updates both adjacency maps together so they never
     * disagree with each other about which edges exist. */
    private void indexEdge(UUID taskId, UUID dependsOnTaskId) {
        dependenciesByTask.computeIfAbsent(taskId, k -> ConcurrentHashMap.newKeySet()).add(dependsOnTaskId);
        dependentsByTask.computeIfAbsent(dependsOnTaskId, k -> ConcurrentHashMap.newKeySet()).add(taskId);
    }
}
