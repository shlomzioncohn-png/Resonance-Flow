// Originally copied from chain/ruthHoltzman/dependency-repository-66; logging added since.

package com.rnd.chain.repository;

import com.rnd.chain.dto.TaskDependency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * In-memory implementation of {@link TaskDependencyRepository}, indexed by task id in both
 * directions (see code review finding: "TaskEligibilityService cycle-check is O(V*E) instead
 * of O(V+E), held under the global lock"). The previous implementation stored edges as one
 * synchronized flat list, so both findByTaskId and findByDependsOnTaskId were a full O(E) linear
 * scan under a lock - and since TaskEligibilityService.wouldCreateCycle's DFS calls findByTaskId
 * once per visited node, one cycle check cost O(V*E) total, all of it held under
 * the graph lock (a de facto single-writer bottleneck that gets worse as the graph grows).
 *
 * <p><b>Invariant</b> (must hold after every {@link #save} / {@link #delete}):
 * for every dependency edge {@code A -> B} (A depends on B):
 * <pre>
 *     B is in dependenciesByTask.get(A)   AND   A is in dependentsByTask.get(B)
 * </pre>
 * i.e. an edge exists if and only if it appears in both maps at once. {@code save} adds to both
 * maps, {@code delete} removes from both maps, within the same write-lock scope, so the two maps
 * never observably disagree about which edges exist.
 *
 * <p><b>Complexity:</b> a map lookup ({@code get}) is O(1) average, but a {@code find...} call
 * also has to materialize the matching edges into a list, which is O(k) where k is the number of
 * edges found for that task. So the total cost of a find call is O(k), not O(1) - the O(1) part
 * is only the initial map access.
 *
 * <p><b>Atomicity / visibility guarantees:</b> {@code save}/{@code delete} are atomic with
 * respect to other writes AND reads (guarded by a {@link ReadWriteLock}: the exists-check and the
 * update to both maps happen inside one write-lock scope, exclusive against both other writers
 * and readers). {@code find...}/{@code existsDependency} take the shared read lock, so multiple
 * readers still run concurrently with each other, but never observe a write mid-flight - closing
 * the torn-view gap a single-{@code synchronized}-block version would have (review finding #3).
 *
 * <p><b>Known limitation:</b> the write lock is a single global lock, so unrelated edges
 * (different task ids) serialize against each other unnecessarily. Deliberately not striped
 * per-key - not worth the added complexity for a temporary in-memory stand-in. Tracked as issue
 * #299.
 *
 * @see <a href="https://github.com/KamaTechOrg/Resonance-Flow/issues/299">#299</a>
 */
public class InMemoryTaskDependencyRepository implements TaskDependencyRepository {

    private static final Logger log = LoggerFactory.getLogger(InMemoryTaskDependencyRepository.class);

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
                Objects.requireNonNull(edge, "edges must not contain null elements");
                indexEdge(edge.taskId(), edge.dependsOnTaskId());
            }
        } finally {
            lock.writeLock().unlock();
        }
        log.debug("Loaded {} dependency edge(s)", edges.size());
    }

    @Override
    public List<TaskDependency> findByTaskId(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        lock.readLock().lock();
        try {
            return toTaskDependencyList(dependenciesByTask.getOrDefault(taskId, Set.of()),
                    dependsOnTaskId -> new TaskDependency(taskId, dependsOnTaskId));
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Overrides the interface default to skip {@link #lock} entirely - safe only because every
     * caller of this method already runs inside {@code TaskEligibilityService}'s
     * the graph lock, which is exclusive with every write this repository ever
     * makes ({@link #save}/{@link #delete} are only ever invoked from inside that same lock).
     * See the interface Javadoc ({@link TaskDependencyRepository#findByTaskIdUnderExternalLock})
     * for the full contract this override relies on. Avoids an uncontended (but not free)
     * read-lock acquire/release on every node a cycle-detection DFS visits (review finding).
     */
    @Override
    public List<TaskDependency> findByTaskIdUnderExternalLock(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        return toTaskDependencyList(dependenciesByTask.getOrDefault(taskId, Set.of()),
                dependsOnTaskId -> new TaskDependency(taskId, dependsOnTaskId));
    }

    @Override
    public List<TaskDependency> findByDependsOnTaskId(UUID taskId) {
        Objects.requireNonNull(taskId, "taskId must not be null");
        lock.readLock().lock();
        try {
            return toTaskDependencyList(dependentsByTask.getOrDefault(taskId, Set.of()),
                    dependentTaskId -> new TaskDependency(dependentTaskId, taskId));
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
    public Optional<TaskDependency> save(TaskDependency dependency) {
        Objects.requireNonNull(dependency, "dependency must not be null");
        UUID taskId = dependency.taskId();
        UUID dependsOnTaskId = dependency.dependsOnTaskId();
        lock.writeLock().lock();
        try {
            boolean added = indexEdge(taskId, dependsOnTaskId);
            if (!added) {
                log.debug("Dependency already exists: task {} depends on {} - no-op", taskId, dependsOnTaskId);
                return Optional.empty();
            }
            log.info("Saved dependency: task {} depends on {}", taskId, dependsOnTaskId);
            return Optional.of(dependency);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public boolean delete(UUID taskId, UUID dependsOnTaskId) {
        lock.writeLock().lock();
        try {
            Set<UUID> dependsOn = dependenciesByTask.get(taskId);
            boolean removed = dependsOn != null && dependsOn.remove(dependsOnTaskId);
            if (!removed) {
                log.debug("Dependency not found for delete: task {} depends on {} - no-op", taskId, dependsOnTaskId);
                return false;
            }
            pruneIfEmpty(dependenciesByTask, taskId, dependsOn);
            Set<UUID> dependents = dependentsByTask.get(dependsOnTaskId);
            if (dependents != null) {
                dependents.remove(taskId);
                pruneIfEmpty(dependentsByTask, dependsOnTaskId, dependents);
            }
            log.info("Deleted dependency: task {} depends on {}", taskId, dependsOnTaskId);
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Shared by every {@code find...} method: maps each id in {@code ids} through {@code toEdge}
     * into a pre-sized {@link ArrayList} (review finding - {@code Collectors.toList()} gives no
     * capacity hint, so the result would otherwise grow/copy internally as elements accumulate). */
    private static List<TaskDependency> toTaskDependencyList(Set<UUID> ids, Function<UUID, TaskDependency> toEdge) {
        return ids.stream().map(toEdge).collect(Collectors.toCollection(() -> new ArrayList<>(ids.size())));
    }

    /**
     * Adds the edge to both adjacency maps. Must run under {@link #lock}'s write lock.
     *
     * @return true if this was a new edge (not already present).
     */
    private boolean indexEdge(UUID taskId, UUID dependsOnTaskId) {
        boolean added = dependenciesByTask.computeIfAbsent(taskId, id -> ConcurrentHashMap.newKeySet())
                .add(dependsOnTaskId);
        dependentsByTask.computeIfAbsent(dependsOnTaskId, id -> ConcurrentHashMap.newKeySet())
                .add(taskId);
        return added;
    }

    /**
     * Drops {@code key} from {@code map} once its edge set is empty (PLAN.md Finding #6), rather
     * than leaking one permanent empty-Set entry per task id ever touched. The two-arg
     * {@code remove} is race-safe against a concurrent add landing on this same key between the
     * emptiness check and the removal.
     */
    private static void pruneIfEmpty(Map<UUID, Set<UUID>> map, UUID key, Set<UUID> set) {
        if (set.isEmpty()) {
            map.remove(key, set);
        }
    }
}
