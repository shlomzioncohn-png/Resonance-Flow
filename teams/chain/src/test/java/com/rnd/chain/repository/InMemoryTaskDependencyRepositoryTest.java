package com.rnd.chain.repository;

import com.rnd.chain.dto.TaskDependency;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryTaskDependencyRepositoryTest {

    @Test
    void save_sameEdgeTwice_isAddedOnlyOnce() {
        UUID taskId = UUID.randomUUID();
        UUID dependsOnTaskId = UUID.randomUUID();
        InMemoryTaskDependencyRepository repo = new InMemoryTaskDependencyRepository(List.of());

        repo.save(new TaskDependency(taskId, dependsOnTaskId));
        repo.save(new TaskDependency(taskId, dependsOnTaskId));

        assertEquals(1, repo.findByTaskId(taskId).size());
    }

    @Test
    void delete_nonexistentEdge_isNoOp() {
        InMemoryTaskDependencyRepository repo = new InMemoryTaskDependencyRepository(List.of());
        UUID taskId = UUID.randomUUID();
        UUID dependsOnTaskId = UUID.randomUUID();

        assertDoesNotThrow(() -> repo.delete(taskId, dependsOnTaskId));
        assertTrue(repo.findByTaskId(taskId).isEmpty());
    }

    @Test
    void delete_lastEdgeForKey_removesMapEntryEntirely() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID dependsOnTaskId = UUID.randomUUID();
        InMemoryTaskDependencyRepository repo = new InMemoryTaskDependencyRepository(
                List.of(new TaskDependency(taskId, dependsOnTaskId)));

        repo.delete(taskId, dependsOnTaskId);

        assertTrue(repo.findByTaskId(taskId).isEmpty());
        // Observable behavior alone can't distinguish "key removed" from "key present with an
        // empty Set" (findByTaskId returns an empty list either way) - reach into the private
        // maps to confirm removeFromIndex actually drops the map entry, per PLAN.md Finding #11.
        assertMapHasNoEntryFor("dependenciesByTask", repo, taskId);
        assertMapHasNoEntryFor("dependentsByTask", repo, dependsOnTaskId);
    }

    @SuppressWarnings("unchecked")
    private static void assertMapHasNoEntryFor(String fieldName, InMemoryTaskDependencyRepository repo, UUID key)
            throws Exception {
        Field field = InMemoryTaskDependencyRepository.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        Map<UUID, ?> map = (Map<UUID, ?>) field.get(repo);
        assertFalse(map.containsKey(key), fieldName + " must not retain an entry for " + key + " once its edge set is empty");
    }

    @Test
    void findByTaskId_and_findByDependsOnTaskId_areDirectional() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        InMemoryTaskDependencyRepository repo = new InMemoryTaskDependencyRepository(
                List.of(new TaskDependency(a, b)));

        assertEquals(1, repo.findByTaskId(a).size(), "A depends on B, so findByTaskId(A) must return the edge");
        assertTrue(repo.findByDependsOnTaskId(a).isEmpty(), "nothing depends on A, so findByDependsOnTaskId(A) must be empty");
        assertEquals(1, repo.findByDependsOnTaskId(b).size(), "A depends on B, so findByDependsOnTaskId(B) must return the edge");
        assertTrue(repo.findByTaskId(b).isEmpty(), "B does not depend on anything, so findByTaskId(B) must be empty");
    }

    @Test
    void constructor_seedsFromNonEmptyList() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        InMemoryTaskDependencyRepository repo = new InMemoryTaskDependencyRepository(
                List.of(new TaskDependency(a, b)));

        assertTrue(repo.existsDependency(a, b));
        assertEquals(1, repo.findByTaskId(a).size());
        assertEquals(1, repo.findByDependsOnTaskId(b).size());
    }
}
