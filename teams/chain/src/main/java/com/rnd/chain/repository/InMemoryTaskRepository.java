// Hand-authored for this task (not copied from a teammate's branch) - approved directly in
// chat on 2026-09-03 after the Stage 6 benchmark showed findByParentTaskId as the sole
// remaining bottleneck and every checked branch's version of it was a plain linear scan. See
// class Javadoc below for the branches checked and the indexing approach used instead.

package com.rnd.chain.repository;

import com.rnd.chain.dto.Task;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * In-memory implementation of {@link TaskRepository}. Hand-authored for this task: every branch
 * checked for a faster findByParentTaskId (Riki-Kaplan, Riki-Task-State-Reconciliation,
 * ShifraAisenstain, chain/Shifra/ci-cd, yehudit/implement-dependency-query-&-traversal-API-#51,
 * and ruthHoltzman's own dependency-repository-66) implements it as a plain linear scan over
 * every task - {@code tasksById.values().stream().filter(task -> parentTaskId.equals(...))}.
 * That is exactly the bottleneck the Stage 6 benchmark (10,000 tasks / 20,000 edges) measured:
 * a BFS visiting thousands of nodes calling that scan once per node.
 *
 * <p>This version instead indexes children by parentTaskId - a {@code Map<UUID, Set<UUID>>} of
 * parent -> child ids, mirroring the dual-adjacency-map pattern ruthHoltzman's
 * {@link InMemoryTaskDependencyRepository} already uses for the dependency axis - so
 * findByParentTaskId is a direct map lookup (O(1) average) plus materializing the matches (O(k),
 * k = number of children), instead of an O(n) scan of the whole task set.
 *
 * <p>Root tasks (parentTaskId == null) are deliberately not indexed at all (PLAN.md Finding #2):
 * the only production call site (TaskTraversalService.childNeighborIds) always passes a real,
 * already-validated task id, the interface contract documents "children of the given task" with
 * no root-listing case, and root detection (ancestor traversal) reads
 * {@code Task.getParentTaskId() == null} directly, never through this index. So there is nothing
 * to represent "no parent" as a map key for - {@code findByParentTaskId(null)} short-circuits to
 * an empty list without touching the map, which also means {@link ConcurrentHashMap}'s no-null-
 * keys restriction never comes up.
 *
 * <p>{@code reindexChild} removes the outer map entry entirely once its child set becomes empty
 * (PLAN.md Finding #6), rather than leaving an ever-growing set of empty-Set entries behind as
 * tasks are reparented over the repository's lifetime.
 *
 * <p><b>Atomicity:</b> {@code save}/{@code update} take {@code writeLock} for the duration of
 * the write (updating {@code tasksById} and re-indexing {@code childrenByParentId} together),
 * so a concurrent {@code findByParentTaskId} never observes a task present in one structure but
 * not yet the other. {@code find*} calls take no lock, same as every other repository here.
 *
 * <p>{@code update} leaves a soft-deleted task untouched - the mutator is never applied to it,
 * matching the interface contract's "equivalent to findById(id).map(mutator).map(save)" (findById
 * already reports a soft-deleted task as not found, so the mutator would never see it).
 */
public class InMemoryTaskRepository implements TaskRepository {

    private final Map<UUID, Task> tasksById = new ConcurrentHashMap<>();
    private final Map<UUID, Set<UUID>> childrenByParentId = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();

    public InMemoryTaskRepository(List<Task> tasks) {
        Objects.requireNonNull(tasks, "tasks must not be null");
        for (Task task : tasks) {
            Objects.requireNonNull(task, "tasks must not contain null elements");
            tasksById.put(task.getId(), task);
            indexChild(task);
        }
    }

    @Override
    public Optional<Task> findById(UUID id) {
        Objects.requireNonNull(id, "id must not be null");
        return Optional.ofNullable(tasksById.get(id))
                .filter(InMemoryTaskRepository::isNotDeleted);
    }

    @Override
    public List<Task> findAllById(List<UUID> ids) {
        Objects.requireNonNull(ids, "ids must not be null");
        return ids.stream()
                .map(tasksById::get)
                .filter(Objects::nonNull)
                .filter(InMemoryTaskRepository::isNotDeleted)
                .collect(Collectors.toList());
    }

    @Override
    public List<Task> findByParentTaskId(UUID parentTaskId) {
        if (parentTaskId == null) {
            // Root tasks aren't indexed - see class Javadoc / PLAN.md Finding #2. No caller in
            // this codebase relies on this today; returning empty here is defensive, not a
            // behavior anything depends on.
            return List.of();
        }
        Set<UUID> childIds = childrenByParentId.getOrDefault(parentTaskId, Set.of());
        List<Task> result = new ArrayList<>(childIds.size());
        for (UUID childId : childIds) {
            Task child = tasksById.get(childId);
            if (child != null && isNotDeleted(child)) {
                result.add(child);
            }
        }
        return result;
    }

    /**
     * Note: save is an unconditional overwrite. Racing it against a concurrent {@link #update}
     * on the same id is a last-write-wins race - prefer {@link #update} when the new value
     * depends on the current one.
     */
    @Override
    public Task save(Task task) {
        Objects.requireNonNull(task, "task must not be null");
        synchronized (writeLock) {
            Task previous = tasksById.put(task.getId(), task);
            reindexChild(previous, task);
        }
        return task;
    }

    @Override
    public Optional<Task> update(UUID id, UnaryOperator<Task> mutator) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(mutator, "mutator must not be null");
        synchronized (writeLock) {
            Task existing = tasksById.get(id);
            if (existing == null || !isNotDeleted(existing)) {
                // A soft-deleted task is left untouched (mutator is not applied to it),
                // matching findById().map(mutator).map(save) semantics.
                return Optional.empty();
            }
            Task updated = Objects.requireNonNull(mutator.apply(existing),
                    "mutator must not return null");
            tasksById.put(id, updated);
            reindexChild(existing, updated);
            return Optional.of(updated)
                    .filter(InMemoryTaskRepository::isNotDeleted);
        }
    }

    private void indexChild(Task task) {
        UUID parentId = task.getParentTaskId();
        if (parentId == null) {
            // Root tasks aren't indexed - see class Javadoc / PLAN.md Finding #2.
            return;
        }
        childrenByParentId.computeIfAbsent(parentId, key -> ConcurrentHashMap.newKeySet())
                .add(task.getId());
    }

    /** Must be called while holding {@code writeLock}. */
    private void reindexChild(Task previous, Task updated) {
        if (previous != null) {
            UUID previousParentId = previous.getParentTaskId();
            if (previousParentId != null) {
                Set<UUID> oldSiblings = childrenByParentId.get(previousParentId);
                if (oldSiblings != null) {
                    oldSiblings.remove(previous.getId());
                    if (oldSiblings.isEmpty()) {
                        // Drop the now-empty entry entirely (PLAN.md Finding #6) rather than
                        // leaking one permanent empty-Set entry per parent id ever touched. The
                        // two-arg remove is race-safe against a concurrent add landing on this
                        // same key between the isEmpty() check and the removal.
                        childrenByParentId.remove(previousParentId, oldSiblings);
                    }
                }
            }
        }
        indexChild(updated);
    }

    /**
     * Soft-deleted tasks (deletedAt != null) are treated as not found.
     */
    private static boolean isNotDeleted(Task task) {
        return task.getDeletedAt() == null;
    }
}