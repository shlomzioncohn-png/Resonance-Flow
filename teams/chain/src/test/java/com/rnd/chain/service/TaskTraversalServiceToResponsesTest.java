package com.rnd.chain.service;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.dto.TaskReadinessSnapshot;
import com.rnd.chain.dto.TaskResponse;
import com.rnd.chain.repository.TaskDependencyRepository;
import com.rnd.chain.repository.TaskReadinessRepository;
import com.rnd.chain.repository.TaskRepository;
import com.rnd.chain.testsupport.TaskFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Mock-based, so it can count queries; the Postgres-backed TaskTraversalServiceTest covers the graph logic. */
class TaskTraversalServiceToResponsesTest {

    private final UUID start = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();
    private final UUID c = UUID.randomUUID();

    private TaskRepository taskRepository;
    private TaskDependencyRepository dependencyRepository;
    private TaskReadinessRepository readinessRepository;
    private TaskTraversalService service;

    @BeforeEach
    void setUp() {
        taskRepository = mock(TaskRepository.class);
        dependencyRepository = mock(TaskDependencyRepository.class);
        readinessRepository = mock(TaskReadinessRepository.class);
        service = new TaskTraversalService(taskRepository, dependencyRepository, readinessRepository);

        when(taskRepository.findById(start)).thenReturn(Optional.of(TaskFixtures.newTask(start)));
        when(dependencyRepository.findByTaskId(start))
                .thenReturn(List.of(new TaskDependency(start, b), new TaskDependency(start, c)));
    }

    private static TaskReadinessSnapshot snapshot(UUID id, int blocking, int openConstraints) {
        return new TaskReadinessSnapshot(id, TaskFixtures.DEFAULT_TENANT_ID, blocking, openConstraints);
    }

    @Test
    void toResponses_usesOneTaskQueryAndOneReadinessQuery() {
        when(taskRepository.findAllById(any())).thenReturn(
                List.of(TaskFixtures.newTask(b), TaskFixtures.newTask(c)));
        when(readinessRepository.findByIds(any())).thenReturn(
                Map.of(b, snapshot(b, 0, 0), c, snapshot(c, 0, 0)));

        service.getDirectDependencies(start);

        verify(taskRepository, times(1)).findAllById(List.of(b, c));
        verify(readinessRepository, times(1)).findByIds(List.of(b, c));
        // The only findById is requireTask(start); toResponses no longer looks tasks up one by one.
        verify(taskRepository, times(1)).findById(any());
    }

    @Test
    void toResponses_keepsInputOrder_evenWhenFindAllByIdReturnsAnotherOrder() {
        when(taskRepository.findAllById(any())).thenReturn(
                List.of(TaskFixtures.newTask(c), TaskFixtures.newTask(b)));
        when(readinessRepository.findByIds(any())).thenReturn(
                Map.of(b, snapshot(b, 0, 0), c, snapshot(c, 0, 0)));

        List<TaskResponse> result = service.getDirectDependencies(start);

        assertThat(result).extracting(TaskResponse::getId).containsExactly(b, c);
    }

    @Test
    void toResponses_skipsAnIdWithNoTask() {
        when(taskRepository.findAllById(any())).thenReturn(List.of(TaskFixtures.newTask(b)));
        when(readinessRepository.findByIds(any())).thenReturn(Map.of(b, snapshot(b, 0, 0)));

        List<TaskResponse> result = service.getDirectDependencies(start);

        assertThat(result).extracting(TaskResponse::getId).containsExactly(b);
    }

    @Test
    void toResponses_skipsATaskWithNoReadinessRow_insteadOfThrowing() {
        when(taskRepository.findAllById(any())).thenReturn(
                List.of(TaskFixtures.newTask(b), TaskFixtures.newTask(c)));
        when(readinessRepository.findByIds(any())).thenReturn(Map.of(b, snapshot(b, 0, 0)));

        List<TaskResponse> result = service.getDirectDependencies(start);

        assertThat(result).extracting(TaskResponse::getId).containsExactly(b);
    }

    @Test
    void toResponses_takesReadinessFromTheSnapshot_notFromTheCountersStoredOnTask() {
        Task base = TaskFixtures.newTask(b);
        Task storedAsBlocked = Task.builder()
            .id(b)
            .tenantId(base.getTenantId())
            .status(base.getStatus())
            .createdAt(base.getCreatedAt())
            .updatedAt(base.getUpdatedAt())
            .build();
        when(taskRepository.findAllById(any())).thenReturn(List.of(storedAsBlocked));
        when(readinessRepository.findByIds(any())).thenReturn(Map.of(b, snapshot(b, 0, 0)));

        TaskResponse response = service.getDirectDependencies(start).get(0);

        assertThat(response.getBlockingCount()).isZero();
        assertThat(response.getOpenConstraintCount()).isZero();
        assertThat(response.isReady()).isTrue();
    }
}
