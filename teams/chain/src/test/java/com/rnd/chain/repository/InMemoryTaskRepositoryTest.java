package com.rnd.chain.repository;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskTestFixtures;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryTaskRepositoryTest {

    @Test
    void findById_returnsExistingTask() {
        UUID id = UUID.randomUUID();
        Task task = TaskTestFixtures.defaultTask(id).build();
        InMemoryTaskRepository repo = new InMemoryTaskRepository(List.of(task));

        Task found = repo.findById(id).orElseThrow();
        assertEquals(id, found.getId());
        assertNotSame(task, found, "findById must return a defensive copy, not the stored instance");
    }

    @Test
    void findById_mutatingReturnedTask_doesNotAffectStoredState() {
        UUID id = UUID.randomUUID();
        Task task = TaskTestFixtures.defaultTask(id).build();
        InMemoryTaskRepository repo = new InMemoryTaskRepository(List.of(task));

        Task found = repo.findById(id).orElseThrow();
        found.setBlockingCount(99);

        assertEquals(0, repo.findById(id).orElseThrow().getBlockingCount(),
                "mutating a Task returned by findById must not leak into repository state without an explicit save()");
    }

    @Test
    void findById_unknownId_returnsEmpty() {
        InMemoryTaskRepository repo = new InMemoryTaskRepository(List.of());

        assertTrue(repo.findById(UUID.randomUUID()).isEmpty());
    }

    @Test
    void findById_softDeletedTask_treatedAsNotFound() {
        UUID id = UUID.randomUUID();
        Task deleted = TaskTestFixtures.defaultTask(id).deletedAt(LocalDateTime.now()).build();
        InMemoryTaskRepository repo = new InMemoryTaskRepository(List.of(deleted));

        assertTrue(repo.findById(id).isEmpty());
    }

    @Test
    void findAllById_omitsMissingAndSoftDeletedIds_withoutThrowing() {
        UUID present = UUID.randomUUID();
        UUID deleted = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        InMemoryTaskRepository repo = new InMemoryTaskRepository(List.of(
                TaskTestFixtures.defaultTask(present).build(),
                TaskTestFixtures.defaultTask(deleted).deletedAt(LocalDateTime.now()).build()));

        List<Task> result = repo.findAllById(List.of(present, deleted, missing));

        assertEquals(1, result.size());
        assertEquals(present, result.get(0).getId());
    }

    @Test
    void findByParentTaskId_filtersByParent_excludesSoftDeleted() {
        UUID parentId = UUID.randomUUID();
        UUID child = UUID.randomUUID();
        UUID deletedChild = UUID.randomUUID();
        UUID otherParentChild = UUID.randomUUID();
        InMemoryTaskRepository repo = new InMemoryTaskRepository(List.of(
                TaskTestFixtures.defaultTask(child).parentTaskId(parentId).build(),
                TaskTestFixtures.defaultTask(deletedChild).parentTaskId(parentId).deletedAt(LocalDateTime.now()).build(),
                TaskTestFixtures.defaultTask(otherParentChild).parentTaskId(UUID.randomUUID()).build()));

        List<Task> children = repo.findByParentTaskId(parentId);

        assertEquals(1, children.size());
        assertEquals(child, children.get(0).getId());
    }

    @Test
    void save_newTask_isRetrievable() {
        InMemoryTaskRepository repo = new InMemoryTaskRepository(List.of());
        UUID id = UUID.randomUUID();
        Task task = TaskTestFixtures.defaultTask(id).build();

        repo.save(task);

        Task found = repo.findById(id).orElseThrow();
        assertEquals(id, found.getId());
        assertNotSame(task, found, "findById must return a defensive copy, not the stored instance");
    }

    @Test
    void save_existingId_upsertsReplacingPreviousValue() {
        UUID id = UUID.randomUUID();
        Task original = TaskTestFixtures.defaultTask(id).build();
        InMemoryTaskRepository repo = new InMemoryTaskRepository(List.of(original));

        Task updated = TaskTestFixtures.defaultTask(id).build();
        updated.setBlockingCount(5);
        repo.save(updated);

        assertEquals(5, repo.findById(id).orElseThrow().getBlockingCount());
    }
}
