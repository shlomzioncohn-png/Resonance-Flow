package com.rnd.chain.repository;

import com.rnd.chain.dto.TaskDependency;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
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
 * own separate O(E) scan (anyMatch) to check for a duplicate edge, on top of whatever the
 * caller already checked via edgeExists().
 *
 * <p>Two {@link ConcurrentHashMap}&lt;UUID, Set&lt;UUID&gt;&gt; adjacency maps mirror each
 * other: {@code dependenciesByTask} (taskId -&gt; the set of ids it depends on) and
 * {@code dependentsByTask} (dependsOnTaskId -&gt; the set of ids that depend on it). Both
 * findByTaskId and findByDependsOnTaskId are now O(1) average plus O(k) to materialize the
 * result, where k is that one task's out-/in-degree - not the whole graph's edge count. save()
 * relies on {@link Set#add} to tell whether an edge was already present, so it no longer needs
 * a separate duplicate-check scan.
 */
public class InMemoryTaskDependencyRepository implements TaskDependencyRepository {

    private final Map<UUID, Set<UUID>> dependenciesByTask = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> dependentsByTask = new ConcurrentHashMap<>();
    // ReadWriteLock rather than a plain writeLock (see review finding #3): the two adjacency
    // maps above are updated sequentially, not atomically, so a reader with no lock at all
    // could observe one map already updated and the other not yet - a torn view of the same
    // logical edge set. Guarding writes AND reads through one ReadWriteLock closes that:
    // multiple readers still run concurrently (readLock is shared), but a reader can never
    // observe a write mid-flight (writeLock is exclusive against both readers and writers).
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public InMemoryTaskDependencyRepository(List<TaskDependency> edges) {
        Objects.requireNonNull(edges, "edges must not be null");
        lock.writeLock().lock();
        try {
            for (TaskDependency edge : edges) {
                indexEdge(edge.getTaskId(), edge.getDependsOnTaskId());
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public List<TaskDependency> findByTaskId(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        lock.readLock().lock();
        try {
            return dependenciesByTask.getOrDefault(taskId, Set.of()).stream()
                    .map(dependsOnTaskId -> new TaskDependency(taskId, dependsOnTaskId))
                    .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public List<TaskDependency> findByDependsOnTaskId(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        lock.readLock().lock();
        try {
            return dependentsByTask.getOrDefault(taskId, Set.of()).stream()
                    .map(dependentTaskId -> new TaskDependency(dependentTaskId, taskId))
                    .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public boolean existsDependency(UUID taskId, UUID dependsOnTaskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(dependsOnTaskId, "dependsOnTaskId must not be null");
        lock.readLock().lock();
        try {
            return dependenciesByTask.getOrDefault(taskId, Set.of()).contains(dependsOnTaskId);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public TaskDependency save(TaskDependency dependency) {
        Objects.requireNonNull(dependency, "dependency must not be null");
        lock.writeLock().lock();
        try {
            indexEdge(dependency.getTaskId(), dependency.getDependsOnTaskId());
        } finally {
            lock.writeLock().unlock();
        }
        return dependency;
    }

    @Override
    public void delete(UUID taskId, UUID dependsOnTaskId) {
        lock.writeLock().lock();
        try {
            removeFromIndex(dependenciesByTask, taskId, dependsOnTaskId);
            removeFromIndex(dependentsByTask, dependsOnTaskId, taskId);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Must run under {@link #lock}'s write lock: removes id from the set at key in map,
     * dropping the map entry entirely if the set becomes empty. Mirrors {@link #indexEdge}'s
     * shape for the removal direction (review finding #21). */
    private static void removeFromIndex(Map<UUID, Set<UUID>> map, UUID key, UUID id) {
        Set<UUID> set = map.get(key);
        if (set != null) {
            set.remove(id);
            if (set.isEmpty()) {
                map.remove(key);
            }
        }
    }

    /** Must run under {@link #lock}'s write lock: updates both adjacency maps together so they
     * never disagree with each other about which edges exist. */
    private void indexEdge(UUID taskId, UUID dependsOnTaskId) {
        dependenciesByTask.computeIfAbsent(taskId, k -> ConcurrentHashMap.newKeySet()).add(dependsOnTaskId);
        dependentsByTask.computeIfAbsent(dependsOnTaskId, k -> ConcurrentHashMap.newKeySet()).add(taskId);
    }
}
