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
 * a dual adjacency map instead of a single scanned list, so lookups by
 * either side of an edge are a direct map access instead of a linear scan.
 *
 * <p><b>Invariant</b> (must hold after every {@link #save} / {@link #delete}):
 * for every dependency edge {@code A -> B} (A depends on B):
 * <pre>
 *     B is in dependenciesByTask.get(A)   AND   A is in dependentsByTask.get(B)
 * </pre>
 * i.e. an edge exists if and only if it appears in both maps at once.
 * {@code save} adds to both maps, {@code delete} removes from both maps,
 * within the same {@code synchronized} block, so the two maps never
 * observably disagree about which edges exist.
 *
 * <p><b>Complexity:</b> a map lookup ({@code get}) is O(1) average, but a
 * {@code find...} call also has to materialize the matching edges into a
 * list, which is O(k) where k is the number of edges found for that task.
 * So the total cost of a find call is O(k), not O(1) - the O(1) part is
 * only the initial map access.
 *
 * <p><b>Atomicity guarantees:</b> {@code save}/{@code delete} are atomic
 * with respect to other writes (guarded by {@code writeLock}: the
 * exists-check and the update to both maps happen inside one lock).
 * {@code find...} calls take no lock at all. There is no guarantee that a
 * reader sees a consistent snapshot across <em>both</em> maps together -
 * only that each individual map read is consistent. This is not a problem
 * today because every read method only ever touches one of the two maps
 * ({@link #findByTaskId} reads only {@code dependenciesByTask},
 * {@link #findByDependsOnTaskId} reads only {@code dependentsByTask}). If
 * a future caller needs a cross-map snapshot (e.g. a method that returns
 * both dependencies and dependents of a task atomically together), this
 * design is not sufficient and needs to be revisited.
 *
 * <p><b>Known limitation - single global write lock:</b> {@code writeLock}
 * serializes every {@code save}/{@code delete} call across the entire
 * repository, regardless of which task ids are involved - two unrelated
 * edges (e.g. {@code A->B} and {@code X->Y}) block each other unnecessarily.
 * A per-key locking strategy (e.g. striping) would allow more concurrent
 * throughput, but is deliberately not implemented here: this class is an
 * in-memory stand-in until a real database is chosen, so the added
 * complexity (fixed lock-acquisition ordering to avoid deadlock, stripe
 * sizing) is not considered worth it for a temporary component.
 */
public class InMemoryTaskDependencyRepository implements TaskDependencyRepository {

    private final ConcurrentHashMap<UUID, Set<UUID>> dependenciesByTask = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Set<UUID>> dependentsByTask = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();

    public InMemoryTaskDependencyRepository(List<TaskDependency> edges) {
        Objects.requireNonNull(edges, "edges must not be null");
        for (TaskDependency edge : edges) {
            Objects.requireNonNull(edge, "edges must not contain null elements");
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
            removeIfEmpty(dependenciesByTask, taskId, dependsOn);

            Set<UUID> dependents = dependentsByTask.get(dependsOnTaskId);
            if (dependents != null) {
                dependents.remove(taskId);
                removeIfEmpty(dependentsByTask, dependsOnTaskId, dependents);
            }
            return true;
        }
    }

    /**
     * Drops {@code key} from {@code map} once its edge set is empty, so
     * adjacency-map entries don't accumulate forever for tasks whose edges
     * have all been removed. Callers must hold {@code writeLock}.
     */
    private static void removeIfEmpty(ConcurrentHashMap<UUID, Set<UUID>> map, UUID key, Set<UUID> edges) {
        if (edges.isEmpty()) {
            map.remove(key);
        }
    }
}
