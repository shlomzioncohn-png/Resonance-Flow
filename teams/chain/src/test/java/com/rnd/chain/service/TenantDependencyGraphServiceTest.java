package com.rnd.chain.service;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.dto.TaskReadinessSnapshot;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.dto.TenantDependencyGraphNode;
import com.rnd.chain.dto.TenantDependencyGraphResponse;
import com.rnd.chain.repository.TaskDependencyRepository;
import com.rnd.chain.repository.TaskReadinessRepository;
import com.rnd.chain.repository.TaskRepository;
import com.rnd.chain.repository.TaskStateHistoryRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeout;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantDependencyGraphServiceTest {

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private TaskDependencyRepository taskDependencyRepository;

    @Mock
    private TaskReadinessRepository taskReadinessRepository;

    @Mock
    private TaskStateHistoryRepository taskStateHistoryRepository;

    private TenantDependencyGraphService service;

    @BeforeEach
    void setUp() {
        service = new TenantDependencyGraphService(
                taskRepository,
                taskDependencyRepository,
                taskReadinessRepository,
                taskStateHistoryRepository);
    }

    @Test
    void emptyTenant_returnsEmptyGraph() {
        UUID tenantId = UUID.randomUUID();

        when(taskRepository.findByTenantId(tenantId))
                .thenReturn(List.of());

        TenantDependencyGraphResponse response =
                service.getDependencyGraph(tenantId, 500);

        assertTrue(response.nodes().isEmpty());
        assertTrue(response.edges().isEmpty());
        assertFalse(response.hasMore());
    }

    @Test
    void dependencyChain_calculatesTransitiveDependentsCorrectly() {
        UUID tenantId = UUID.randomUUID();

        UUID taskAId = UUID.randomUUID();
        UUID taskBId = UUID.randomUUID();
        UUID taskCId = UUID.randomUUID();

        Task taskA = task(taskAId, tenantId);
        Task taskB = task(taskBId, tenantId);
        Task taskC = task(taskCId, tenantId);

        List<UUID> taskIds =
                List.of(taskAId, taskBId, taskCId);

        when(taskRepository.findByTenantId(tenantId))
                .thenReturn(List.of(taskA, taskB, taskC));

        when(taskDependencyRepository.findByTaskIds(taskIds))
                .thenReturn(List.of(
                        new TaskDependency(taskAId, taskBId),
                        new TaskDependency(taskBId, taskCId)));

        when(taskReadinessRepository.findByIds(taskIds))
                .thenReturn(Map.of(
                        taskAId,
                        new TaskReadinessSnapshot(taskAId, tenantId, 1, 0),
                        taskBId,
                        new TaskReadinessSnapshot(taskBId, tenantId, 1, 0),
                        taskCId,
                        new TaskReadinessSnapshot(taskCId, tenantId, 0, 0)));

        when(taskStateHistoryRepository
                .findLatestBlockedSinceByTaskIds(taskIds))
                .thenReturn(Map.of());

        TenantDependencyGraphResponse response =
                service.getDependencyGraph(tenantId, 500);

        assertEquals(3, response.nodes().size());

        assertEquals(
                taskCId,
                response.nodes().get(0).taskId());

        assertEquals(
                2,
                response.nodes().get(0)
                        .transitiveDependentsCount());

        assertEquals(
                taskBId,
                response.nodes().get(1).taskId());

        assertEquals(
                1,
                response.nodes().get(1)
                        .transitiveDependentsCount());

        assertEquals(
                taskAId,
                response.nodes().get(2).taskId());

        assertEquals(
                0,
                response.nodes().get(2)
                        .transitiveDependentsCount());

        assertEquals(2, response.edges().size());
        assertFalse(response.hasMore());
    }

    @Test
    void limitExceeded_returnsHighestImpactNodesAndHasMoreTrue() {
        UUID tenantId = UUID.randomUUID();

        UUID taskAId = UUID.randomUUID();
        UUID taskBId = UUID.randomUUID();
        UUID taskCId = UUID.randomUUID();

        Task taskA = task(taskAId, tenantId);
        Task taskB = task(taskBId, tenantId);
        Task taskC = task(taskCId, tenantId);

        List<UUID> taskIds =
                List.of(taskAId, taskBId, taskCId);

        when(taskRepository.findByTenantId(tenantId))
                .thenReturn(List.of(taskA, taskB, taskC));

        when(taskDependencyRepository.findByTaskIds(taskIds))
                .thenReturn(List.of(
                        new TaskDependency(taskAId, taskBId),
                        new TaskDependency(taskBId, taskCId)));

        when(taskReadinessRepository.findByIds(taskIds))
                .thenReturn(Map.of(
                        taskAId,
                        new TaskReadinessSnapshot(taskAId, tenantId, 1, 0),
                        taskBId,
                        new TaskReadinessSnapshot(taskBId, tenantId, 1, 0),
                        taskCId,
                        new TaskReadinessSnapshot(taskCId, tenantId, 0, 0)));

        when(taskStateHistoryRepository
                .findLatestBlockedSinceByTaskIds(taskIds))
                .thenReturn(Map.of());

        TenantDependencyGraphResponse response =
                service.getDependencyGraph(tenantId, 2);

        assertEquals(2, response.nodes().size());
        assertTrue(response.hasMore());

        assertEquals(
                taskCId,
                response.nodes().get(0).taskId());

        assertEquals(
                taskBId,
                response.nodes().get(1).taskId());

        /*
         * Only B -> C remains because A was removed by the limit.
         */
        assertEquals(1, response.edges().size());

        assertEquals(
                taskBId,
                response.edges().get(0).taskId());

        assertEquals(
                taskCId,
                response.edges().get(0).dependsOnTaskId());
    }

    @Test
    void getDependencyGraph_diamondGraph_countsEachTransitiveDependentOnce() {
        UUID tenantId = UUID.randomUUID();

        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        UUID d = UUID.randomUUID();

        List<Task> tasks =
                List.of(
                        task(a, tenantId),
                        task(b, tenantId),
                        task(c, tenantId),
                        task(d, tenantId));

        /*
         * A depends on B
         * A depends on C
         * B depends on D
         * C depends on D
         *
         * D -> B -> A
         * D -> C -> A
         *
         * A must be counted only once for D.
         */
        List<TaskDependency> dependencies =
                List.of(
                        new TaskDependency(a, b),
                        new TaskDependency(a, c),
                        new TaskDependency(b, d),
                        new TaskDependency(c, d));

        List<UUID> taskIds =
                tasks.stream()
                        .map(Task::getId)
                        .toList();

        when(taskRepository.findByTenantId(tenantId))
                .thenReturn(tasks);

        when(taskDependencyRepository.findByTaskIds(taskIds))
                .thenReturn(dependencies);

        when(taskReadinessRepository.findByIds(taskIds))
                .thenReturn(readiness(tasks));

        when(taskStateHistoryRepository
                .findLatestBlockedSinceByTaskIds(taskIds))
                .thenReturn(Map.of());

        TenantDependencyGraphResponse response =
                service.getDependencyGraph(
                        tenantId,
                        10);

        Map<UUID, Integer> counts =
                new HashMap<>();

        for (TenantDependencyGraphNode node : response.nodes()) {
            counts.put(
                    node.taskId(),
                    node.transitiveDependentsCount());
        }

        assertEquals(0, counts.get(a));
        assertEquals(1, counts.get(b));
        assertEquals(1, counts.get(c));
        assertEquals(3, counts.get(d));
    }

    @Test
    void getDependencyGraph_largeChain_completesWithinReasonableTime() {
        UUID tenantId =
                UUID.randomUUID();

        int taskCount =
                5_000;

        List<Task> tasks =
                new ArrayList<>(taskCount);

        List<TaskDependency> dependencies =
                new ArrayList<>(taskCount - 1);

        for (int i = 0; i < taskCount; i++) {
            tasks.add(
                    task(
                            UUID.randomUUID(),
                            tenantId));
        }

        /*
         * task[0] depends on task[1]
         * task[1] depends on task[2]
         * ...
         *
         * The last task has 4,999 transitive dependents.
         */
        for (int i = 0; i < taskCount - 1; i++) {
            dependencies.add(
                    new TaskDependency(
                            tasks.get(i).getId(),
                            tasks.get(i + 1).getId()));
        }

        List<UUID> taskIds =
                tasks.stream()
                        .map(Task::getId)
                        .toList();

        when(taskRepository.findByTenantId(tenantId))
                .thenReturn(tasks);

        when(taskDependencyRepository.findByTaskIds(taskIds))
                .thenReturn(dependencies);

        when(taskReadinessRepository.findByIds(taskIds))
                .thenReturn(readiness(tasks));

        when(taskStateHistoryRepository
                .findLatestBlockedSinceByTaskIds(taskIds))
                .thenReturn(Map.of());

        TenantDependencyGraphResponse response =
                assertTimeout(
                        Duration.ofSeconds(5),
                        () -> service.getDependencyGraph(
                                tenantId,
                                taskCount));

        assertEquals(
                taskCount,
                response.nodes().size());

        TenantDependencyGraphNode highestImpact =
                response.nodes().get(0);

        assertEquals(
                taskCount - 1,
                highestImpact.transitiveDependentsCount());
    }
    private Task task(
        UUID taskId,
        UUID tenantId) {

        return Task.builder()
            .id(taskId)
            .tenantId(tenantId)
            .status(TaskStatus.ACTIVE)
            .createdAt(LocalDateTime.now())
            .updatedAt(LocalDateTime.now())
            .build();
    }   

    private Map<UUID, TaskReadinessSnapshot> readiness(
            List<Task> tasks) {

        Map<UUID, TaskReadinessSnapshot> result =
                new HashMap<>();

        for (Task task : tasks) {
            result.put(
                    task.getId(),
                    new TaskReadinessSnapshot(
                            task.getId(),
                            task.getTenantId(),
                            0,
                            0));
        }

        return result;
    }
}