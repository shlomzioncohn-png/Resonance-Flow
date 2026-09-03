package com.rnd.chain.service;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.dto.TaskResponse;
import com.rnd.chain.exception.TaskNotFoundException;
import com.rnd.chain.repository.InMemoryTaskDependencyRepository;
import com.rnd.chain.repository.InMemoryTaskRepository;
import com.rnd.chain.repository.TaskDependencyRepository;
import com.rnd.chain.repository.TaskRepository;
import org.junit.jupiter.api.Test;

import java.util.AbstractMap;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskTraversalServiceTest {

    // ---- Dependency axis --------------------------------------------------

    @Test
    void getDirectDependencies_returnsOnlyTheImmediateDependencies() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        // a depends on b, b depends on c - a's direct dependencies is just {b}, not {b, c}.
        TaskTraversalService service = serviceOf(
                tasks(a, b, c),
                dependencies(edge(a, b), edge(b, c)));

        List<TaskResponse> result = service.getDirectDependencies(a);

        assertIds(result, b);
    }

    @Test
    void getDirectDependencies_returnsEmptyList_whenTaskHasNoDependencies() {
        UUID a = UUID.randomUUID();
        TaskTraversalService service = serviceOf(tasks(a), dependencies());

        assertTrue(service.getDirectDependencies(a).isEmpty());
    }

    @Test
    void getDirectDependencies_throwsTaskNotFoundException_whenTaskDoesNotExist() {
        TaskTraversalService service = serviceOf(tasks(), dependencies());

        assertThrows(TaskNotFoundException.class, () -> service.getDirectDependencies(UUID.randomUUID()));
    }

    @Test
    void getAllDependencies_returnsEveryTransitiveDependency_acrossMultipleLevels() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        // a -> b -> c -> d (three levels deep).
        TaskTraversalService service = serviceOf(
                tasks(a, b, c, d),
                dependencies(edge(a, b), edge(b, c), edge(c, d)));

        List<TaskResponse> result = service.getAllDependencies(a);

        assertIds(result, b, c, d);
    }

    @Test
    void getAllDependencies_returnsEachNodeOnce_whenMultiplePathsLeadToTheSameNode() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        // Diamond: a depends on b and c directly; both b and c depend on d.
        // d is reachable via two paths but must appear exactly once.
        TaskTraversalService service = serviceOf(
                tasks(a, b, c, d),
                dependencies(edge(a, b), edge(a, c), edge(b, d), edge(c, d)));

        List<TaskResponse> result = service.getAllDependencies(a);

        assertIds(result, b, c, d);
        assertEquals(1, result.stream().filter(r -> r.getId().equals(d)).count());
    }

    @Test
    void getAllDependencies_terminatesAndExcludesTheCycle_onCyclicData() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        // a -> b -> c -> a: a defensive cycle that write-time validation is supposed to
        // prevent, but the traversal must not loop forever if it ever occurs anyway.
        TaskTraversalService service = serviceOf(
                tasks(a, b, c),
                dependencies(edge(a, b), edge(b, c), edge(c, a)));

        List<TaskResponse> result = service.getAllDependencies(a);

        assertIds(result, b, c);
    }

    @Test
    void getAllDependencies_returnsEmptyList_onAnEmptyGraph() {
        UUID a = UUID.randomUUID();
        TaskTraversalService service = serviceOf(tasks(a), dependencies());

        assertTrue(service.getAllDependencies(a).isEmpty());
    }

    @Test
    void getDirectDependents_returnsOnlyTheImmediateDependents() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        // b depends on a, c depends on b - a's direct dependents is just {b}, not {b, c}.
        TaskTraversalService service = serviceOf(
                tasks(a, b, c),
                dependencies(edge(b, a), edge(c, b)));

        List<TaskResponse> result = service.getDirectDependents(a);

        assertIds(result, b);
    }

    @Test
    void getDirectDependents_returnsEmptyList_whenNothingDependsOnTheTask() {
        UUID a = UUID.randomUUID();
        TaskTraversalService service = serviceOf(tasks(a), dependencies());

        assertTrue(service.getDirectDependents(a).isEmpty());
    }

    @Test
    void getAllDependents_returnsEveryTransitiveDependent_acrossMultipleLevels() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        // d -> c -> b -> a (three levels deep, reverse direction of the dependency edges).
        TaskTraversalService service = serviceOf(
                tasks(a, b, c, d),
                dependencies(edge(b, a), edge(c, b), edge(d, c)));

        List<TaskResponse> result = service.getAllDependents(a);

        assertIds(result, b, c, d);
    }

    @Test
    void getAllDependents_returnsEachNodeOnce_whenMultiplePathsLeadToTheSameNode() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        // Diamond, reversed: b and c both depend on a; d depends on both b and c.
        // d is reachable via two paths but must appear exactly once.
        TaskTraversalService service = serviceOf(
                tasks(a, b, c, d),
                dependencies(edge(b, a), edge(c, a), edge(d, b), edge(d, c)));

        List<TaskResponse> result = service.getAllDependents(a);

        assertIds(result, b, c, d);
        assertEquals(1, result.stream().filter(r -> r.getId().equals(d)).count());
    }

    @Test
    void getAllDependents_terminates_onCyclicData() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        // Same cycle as the dependency-direction test, walked from the other side:
        // a -> b -> c -> a. dependentNeighborIds is a separate neighbor function from
        // dependencyNeighborIds, so this re-verifies the cycle guard on that path too.
        TaskTraversalService service = serviceOf(
                tasks(a, b, c),
                dependencies(edge(a, b), edge(b, c), edge(c, a)));

        List<TaskResponse> result = service.getAllDependents(a);

        assertIds(result, b, c);
    }

    // ---- Hierarchy axis ----------------------------------------------------

    @Test
    void getDirectChildren_returnsOnlyTheImmediateChildren() {
        UUID root = UUID.randomUUID();
        UUID child = UUID.randomUUID();
        UUID grandchild = UUID.randomUUID();
        TaskTraversalService service = serviceOf(
                tasksWithParents(entry(root, null), entry(child, root), entry(grandchild, child)),
                dependencies());

        List<TaskResponse> result = service.getDirectChildren(root);

        assertIds(result, child);
    }

    @Test
    void getDirectChildren_returnsAllOfThem_whenATaskHasMultipleChildren() {
        UUID root = UUID.randomUUID();
        UUID child1 = UUID.randomUUID();
        UUID child2 = UUID.randomUUID();
        UUID child3 = UUID.randomUUID();
        TaskTraversalService service = serviceOf(
                tasksWithParents(entry(root, null), entry(child1, root), entry(child2, root), entry(child3, root)),
                dependencies());

        List<TaskResponse> result = service.getDirectChildren(root);

        assertIds(result, child1, child2, child3);
    }

    @Test
    void getDirectChildren_returnsEmptyList_whenTaskHasNoChildren() {
        UUID a = UUID.randomUUID();
        TaskTraversalService service = serviceOf(tasksWithParents(entry(a, null)), dependencies());

        assertTrue(service.getDirectChildren(a).isEmpty());
    }

    @Test
    void getAllDescendants_returnsEveryDescendant_acrossADeepHierarchy() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        UUID e = UUID.randomUUID();
        // a -> b -> c -> d -> e: five levels deep.
        TaskTraversalService service = serviceOf(
                tasksWithParents(entry(a, null), entry(b, a), entry(c, b), entry(d, c), entry(e, d)),
                dependencies());

        List<TaskResponse> result = service.getAllDescendants(a);

        assertIds(result, b, c, d, e);
    }

    @Test
    void getAllDescendants_returnsEachNodeOnce_whenATaskHasMultipleChildrenWithTheirOwnChildren() {
        UUID root = UUID.randomUUID();
        UUID child1 = UUID.randomUUID();
        UUID child2 = UUID.randomUUID();
        UUID grandchild = UUID.randomUUID();
        // root has two children; only child1 has a child of its own (grandchild). This reuses
        // collectReachableIds on childNeighborIds rather than the dependency neighbor functions,
        // so it's worth its own dedup-shaped check even though the mechanism is shared with
        // the dependency axis tests above.
        TaskTraversalService service = serviceOf(
                tasksWithParents(entry(root, null), entry(child1, root), entry(child2, root), entry(grandchild, child1)),
                dependencies());

        List<TaskResponse> result = service.getAllDescendants(root);

        assertIds(result, child1, child2, grandchild);
        assertEquals(1, result.stream().filter(r -> r.getId().equals(grandchild)).count());
    }

    @Test
    void getAllDescendants_returnsEmptyList_whenTaskHasNoChildren() {
        UUID a = UUID.randomUUID();
        TaskTraversalService service = serviceOf(tasksWithParents(entry(a, null)), dependencies());

        assertTrue(service.getAllDescendants(a).isEmpty());
    }

    @Test
    void getAncestors_returnsTheChainNearestParentFirst_rootLast() {
        UUID root = UUID.randomUUID();
        UUID middle = UUID.randomUUID();
        UUID leaf = UUID.randomUUID();
        // root -> middle -> leaf. leaf's ancestors: [middle, root] - direct parent first.
        TaskTraversalService service = serviceOf(
                tasksWithParents(entry(root, null), entry(middle, root), entry(leaf, middle)),
                dependencies());

        List<TaskResponse> result = service.getAncestors(leaf);

        assertEquals(2, result.size());
        assertEquals(middle, result.get(0).getId());
        assertEquals(root, result.get(1).getId());
    }

    @Test
    void getAncestors_returnsEmptyList_whenTaskHasNoParent() {
        UUID root = UUID.randomUUID();
        TaskTraversalService service = serviceOf(tasksWithParents(entry(root, null)), dependencies());

        assertTrue(service.getAncestors(root).isEmpty());
    }

    @Test
    void getAncestors_terminates_onACyclicParentChain() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        // a's parent is b, b's parent is c, c's parent is a: a cyclic parent chain that should
        // never occur in practice (the hierarchy is supposed to be a tree), but getAncestors
        // doesn't reuse collectReachableIds - it walks parentTaskId with its own visited set -
        // so this needs its own dedicated test rather than inheriting the dependency-axis
        // cycle-protection coverage.
        TaskTraversalService service = serviceOf(
                tasksWithParents(entry(a, b), entry(b, c), entry(c, a)),
                dependencies());

        List<TaskResponse> result = service.getAncestors(a);

        assertEquals(2, result.size());
        assertEquals(b, result.get(0).getId());
        assertEquals(c, result.get(1).getId());
    }

    // ---- fixtures -----------------------------------------------------------

    private static TaskTraversalService serviceOf(List<Task> tasks, List<TaskDependency> edges) {
        TaskRepository taskRepository = new InMemoryTaskRepository(tasks);
        TaskDependencyRepository taskDependencyRepository = new InMemoryTaskDependencyRepository(edges);
        return new TaskTraversalService(taskRepository, taskDependencyRepository);
    }

    private static List<Task> tasks(UUID... ids) {
        return Arrays.stream(ids).map(TaskTraversalServiceTest::task).collect(Collectors.toList());
    }

    private static Task task(UUID id) {
        return Task.builder()
                .id(id)
                .ownerId("owner-" + id)
                .build();
    }

    @SafeVarargs
    private static List<Task> tasksWithParents(Map.Entry<UUID, UUID>... idToParentId) {
        List<Task> result = new java.util.ArrayList<>();
        for (Map.Entry<UUID, UUID> e : idToParentId) {
            result.add(Task.builder()
                    .id(e.getKey())
                    .parentTaskId(e.getValue())
                    .ownerId("owner-" + e.getKey())
                    .build());
        }
        return result;
    }

    private static Map.Entry<UUID, UUID> entry(UUID id, UUID parentId) {
        return new AbstractMap.SimpleEntry<>(id, parentId);
    }

    private static List<TaskDependency> dependencies(TaskDependency... edges) {
        return Arrays.asList(edges);
    }

    private static TaskDependency edge(UUID taskId, UUID dependsOnTaskId) {
        return new TaskDependency(taskId, dependsOnTaskId);
    }

    private static void assertIds(List<TaskResponse> actual, UUID... expected) {
        List<UUID> actualIds = actual.stream().map(TaskResponse::getId).collect(Collectors.toList());
        assertEquals(expected.length, actualIds.size(),
                () -> "expected ids " + Arrays.toString(expected) + " but got " + actualIds);
        for (UUID id : expected) {
            assertTrue(actualIds.contains(id), () -> "expected " + id + " in " + actualIds);
        }
    }
}
