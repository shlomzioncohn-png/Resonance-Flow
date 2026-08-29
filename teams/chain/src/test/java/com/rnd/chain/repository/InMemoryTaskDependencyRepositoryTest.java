package com.rnd.chain.repository;

import com.rnd.chain.dto.TaskDependency;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryTaskDependencyRepositoryTest {

    @Test
    void findByTaskId_returnsOnlyEdgesWhereTaskIsDependent() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        InMemoryTaskDependencyRepository repo = new InMemoryTaskDependencyRepository(List.of(
                new TaskDependency(a, b),
                new TaskDependency(c, a)));

        List<TaskDependency> result = repo.findByTaskId(a);

        assertEquals(1, result.size());
        assertEquals(b, result.get(0).getDependsOnTaskId());
    }

    @Test
    void findByDependsOnTaskId_returnsOnlyEdgesWhereTaskIsBlocker() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        InMemoryTaskDependencyRepository repo = new InMemoryTaskDependencyRepository(List.of(
                new TaskDependency(a, b),
                new TaskDependency(c, b)));

        List<TaskDependency> result = repo.findByDependsOnTaskId(b);

        assertEquals(2, result.size());
    }

    @Test
    void save_newEdge_isRetrievable() {
        InMemoryTaskDependencyRepository repo = new InMemoryTaskDependencyRepository(List.of());
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        repo.save(new TaskDependency(a, b));

        assertEquals(1, repo.findByTaskId(a).size());
    }

    @Test
    void save_duplicateEdge_isIdempotent() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        InMemoryTaskDependencyRepository repo = new InMemoryTaskDependencyRepository(List.of(
                new TaskDependency(a, b)));

        repo.save(new TaskDependency(a, b));

        assertEquals(1, repo.findByTaskId(a).size());
    }

    @Test
    void delete_removesMatchingEdge() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        InMemoryTaskDependencyRepository repo = new InMemoryTaskDependencyRepository(List.of(
                new TaskDependency(a, b)));

        repo.delete(a, b);

        assertTrue(repo.findByTaskId(a).isEmpty());
    }

    @Test
    void delete_nonExistentEdge_isNoOp() {
        InMemoryTaskDependencyRepository repo = new InMemoryTaskDependencyRepository(List.of());

        assertDoesNotThrow(() -> repo.delete(UUID.randomUUID(), UUID.randomUUID()));
    }
}
