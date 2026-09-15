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

/**
 * In-memory implementation of {@link TaskDependencyRepository}, indexed by task id in both
 * directions (see code review finding: "TaskEligibilityService cycle-check is O(V*E) instead
 * of O(V+E), held under the global lock"). The previous implementation stored edges as one
 * synchronized flat list, so both findByTaskId and findByDependsOnTaskId were a full O(E) linear
 * scan under a lock - and since TaskEligibilityService.wouldCreateCycle's DFS calls findByTaskId
 * once per visited node, one cycle check cost O(V*E) total, all of it held under
 * dependencyGraphLock (a de facto single-writer bottleneck that gets worse as the graph grows).
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
 * <p><b>Known limitation:</b> {@code writeLock} is a single global lock,
 * so unrelated edges (different task ids) serialize against each other
 * unnecessarily. Deliberately not striped per-key - not worth the added
 * complexity for a temporary in-memory stand-in. Tracked as issue #299.
 *
 * @see <a href="https://github.com/KamaTechOrg/Resonance-Flow/issues/299">#299</a>
 */
public class InMemoryTaskDependencyRepository implements TaskDependencyRepository {

    private static final Logger log = LoggerFactory.getLogger(InMemoryTaskDependencyRepository.class);

    private final ConcurrentHashMap<UUID, Set<UUID>> dependenciesByTask = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Set<UUID>> dependentsByTask = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();

    public InMemoryTaskDependencyRepository(List<TaskDependency> edges) {
        Objects.requireNonNull(edges, "edges must not be null");
        for (TaskDependency edge : edges) {
            Objects.requireNonNull(edge, "edges must not contain null elements");
            indexEdge(edge.getTaskId(), edge.getDependsOnTaskId());
        }
        log.debug("Loaded {} dependency edge(s)", edges.size());
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
            boolean added = indexEdge(taskId, dependsOnTaskId);
            if (!added) {
                log.debug("Dependency already exists: task {} depends on {} - no-op", taskId, dependsOnTaskId);
                return Optional.empty();
            }
            log.info("Saved dependency: task {} depends on {}", taskId, dependsOnTaskId);
            return Optional.of(dependency);
        }
    }

    @Override
    public boolean delete(UUID taskId, UUID dependsOnTaskId) {
        synchronized (writeLock) {
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
        }
    }

    /**
     * Adds the edge to both adjacency maps. Must run under {@link #writeLock}.
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