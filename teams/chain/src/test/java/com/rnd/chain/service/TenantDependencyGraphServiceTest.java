package com.rnd.chain.service;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.dto.TaskReadinessSnapshot;
import com.rnd.chain.dto.TaskStatus;
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

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

        when(taskRepository.findByTenantId(tenantId))
                .thenReturn(List.of(taskA, taskB, taskC));

        when(taskDependencyRepository.findByTaskIds(
                List.of(taskAId, taskBId, taskCId)))
                .thenReturn(List.of(
                        new TaskDependency(taskAId, taskBId),
                        new TaskDependency(taskBId, taskCId)
                ));

        when(taskReadinessRepository.findByIds(
                List.of(taskAId, taskBId, taskCId)))
                .thenReturn(Map.of(
                        taskAId,
                        new TaskReadinessSnapshot(taskAId, 1, 0),
                        taskBId,
                        new TaskReadinessSnapshot(taskBId, 1, 0),
                        taskCId,
                        new TaskReadinessSnapshot(taskCId, 0, 0)
                ));

        when(taskStateHistoryRepository
                .findLatestBlockedSinceByTaskIds(
                        List.of(taskAId, taskBId, taskCId)))
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

        when(taskRepository.findByTenantId(tenantId))
                .thenReturn(List.of(taskA, taskB, taskC));

        when(taskDependencyRepository.findByTaskIds(
                List.of(taskAId, taskBId, taskCId)))
                .thenReturn(List.of(
                        new TaskDependency(taskAId, taskBId),
                        new TaskDependency(taskBId, taskCId)
                ));

        when(taskReadinessRepository.findByIds(
                List.of(taskAId, taskBId, taskCId)))
                .thenReturn(Map.of(
                        taskAId,
                        new TaskReadinessSnapshot(taskAId, 1, 0),
                        taskBId,
                        new TaskReadinessSnapshot(taskBId, 1, 0),
                        taskCId,
                        new TaskReadinessSnapshot(taskCId, 0, 0)
                ));

        when(taskStateHistoryRepository
                .findLatestBlockedSinceByTaskIds(
                        List.of(taskAId, taskBId, taskCId)))
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

    private Task task(
            UUID taskId,
            UUID tenantId) {

        return Task.builder()
                .id(taskId)
                .tenantId(tenantId)
                .status(TaskStatus.ACTIVE)
                .isReady(true)
                .blockingCount(0)
                .openConstraintCount(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }
}