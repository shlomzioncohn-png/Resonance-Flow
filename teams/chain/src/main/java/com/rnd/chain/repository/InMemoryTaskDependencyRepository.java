// Copied verbatim from chain/ruthHoltzman/dependency-repository-66, unmodified.

package com.rnd.chain.repository;

import com.rnd.chain.dto.TaskDependency;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory implementation of {@link TaskDependencyRepository}, backed by
 * a dual adjacency map so lookups by either side of an edge are a direct
 * map access instead of a linear scan.
 *
 * <p><b>Invariant</b>: for every edge {@code A -> B} (A depends on B),
 * {@code B} is in {@code dependenciesByTask.get(A)} and {@code A} is in
 * {@code dependentsByTask.get(B)}. {@code save}/{@code delete} update both
 * maps inside the same {@code synchronized} block, so they never disagree.
 *
 * <p><b>Atomicity:</b> {@code save}/{@code delete} are atomic against each
 * other via {@code writeLock}. {@code find...} calls take no lock and each
 * reads only one of the two maps, so no cross-map consistency is needed.
 */
public class InMemoryTaskDependencyRepository implements TaskDependencyRepository {

    private final ConcurrentHashMap<UUID, Set<UUID>> dependenciesByTask = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Set<UUID>> dependentsByTask = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();

    public InMemoryTaskDependencyRepository(List<TaskDependency> edges) {
        Objects.requireNonNull(edges, "edges must not be null");
        for (TaskDependency edge : edges) {
            UUID taskId = edge.getTaskId();
            UUID dependsOnTaskId = edge.getDependsOnTaskId();
            dependenciesByTask.computeIfAbsent(taskId, id -> ConcurrentHashMap.newKeySet())
                    .add(dependsOnTaskId);
            dependentsByTask.computeIfAbsent(dependsOnTaskId, id -> ConcurrentHashMap.newKeySet())
                    .add(taskId);
        }
    }

    @Override
    public List<TaskDependency> findByTaskId(UUID taskId) {
        Set<UUID> dependsOn = dependenciesByTask.getOrDefault(taskId, Set.of());
        List<TaskDependency> result = new ArrayList<>(dependsOn.size());
        for (UUID dependsOnTaskId : dependsOn) {
            result.add(new TaskDependency(taskId, dependsOnTaskId));
        }
        return result;
    }

    @Override
    public List<TaskDependency> findByDependsOnTaskId(UUID taskId) {
        Set<UUID> dependents = dependentsByTask.getOrDefault(taskId, Set.of());
        List<TaskDependency> result = new ArrayList<>(dependents.size());
        for (UUID dependentTaskId : dependents) {
            result.add(new TaskDependency(dependentTaskId, taskId));
        }
        return result;
    }

    @Override
    public Optional<TaskDependency> save(TaskDependency dependency) {
        Objects.requireNonNull(dependency, "dependency must not be null");
        UUID taskId = dependency.getTaskId();
        UUID dependsOnTaskId = dependency.getDependsOnTaskId();
        synchronized (writeLock) {
            Set<UUID> dependsOn = dependenciesByTask.computeIfAbsent(taskId, id -> ConcurrentHashMap.newKeySet());
            boolean added = dependsOn.add(dependsOnTaskId);
            if (!added) {
                return Optional.empty();
            }
            dependentsByTask.computeIfAbsent(dependsOnTaskId, id -> ConcurrentHashMap.newKeySet())
                    .add(taskId);
            return Optional.of(dependency);
        }
    }

    @Override
    public boolean delete(UUID taskId, UUID dependsOnTaskId) {
        synchronized (writeLock) {
            Set<UUID> dependsOn = dependenciesByTask.get(taskId);
            boolean removed = dependsOn != null && dependsOn.remove(dependsOnTaskId);
            if (!removed) {
                return false;
            }
            Set<UUID> dependents = dependentsByTask.get(dependsOnTaskId);
            if (dependents != null) {
                dependents.remove(taskId);
            }
            return true;
        }
    }
}
