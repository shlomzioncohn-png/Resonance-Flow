package com.rnd.chain.service;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.dto.TaskTestFixtures;
import com.rnd.chain.exception.TaskNotFoundException;
import com.rnd.chain.repository.GraphLock;
import com.rnd.chain.repository.TaskDependencyRepository;
import com.rnd.chain.repository.TaskReadinessRepository;
import com.rnd.chain.repository.TaskRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DependencyEdgeServiceTenantLockTest {

    private TaskRepository taskRepository;
    private TaskDependencyRepository taskDependencyRepository;
    private GraphLock graphLock;
    private TaskCounterUpdater taskCounterUpdater;
    private TaskStateHistoryRecorder historyRecorder;
    private TaskReadinessRepository taskReadinessRepository;

    private DependencyEdgeService service;

    @BeforeEach
    void setUp() {

        taskRepository =
                mock(TaskRepository.class);

        taskDependencyRepository =
                mock(TaskDependencyRepository.class);

        graphLock =
                mock(GraphLock.class);

        taskCounterUpdater =
                mock(TaskCounterUpdater.class);

        historyRecorder =
                mock(TaskStateHistoryRecorder.class);

        taskReadinessRepository =
                mock(TaskReadinessRepository.class);

        service =
                new DependencyEdgeService(
                        taskDependencyRepository,
                        graphLock,
                        taskCounterUpdater,
                        historyRecorder,
                        taskReadinessRepository,
                        taskRepository);
    }

    @Test
    void addDependency_usesTenantOfExistingTaskForLock() {

        UUID tenantId =
                UUID.randomUUID();

        UUID taskId =
                UUID.randomUUID();

        UUID dependsOnTaskId =
                UUID.randomUUID();

        Task task =
                TaskTestFixtures
                        .defaultTask(taskId)
                        .tenantId(tenantId)
                        .build();

        when(taskRepository.findById(taskId))
                .thenReturn(Optional.of(task));

        TaskDependency expected =
                mock(TaskDependency.class);

        when(graphLock.runExclusive(
                eq(tenantId),
                any(Supplier.class)))
                .thenReturn(expected);

        service.addDependency(
                taskId,
                dependsOnTaskId);

        verify(graphLock)
                .runExclusive(
                        eq(tenantId),
                        any(Supplier.class));
    }

    @Test
    void addDependencyCreatingMissing_usesTenantFromEventDirectly() {

        UUID eventTenantId =
                UUID.randomUUID();

        UUID taskId =
                UUID.randomUUID();

        UUID dependsOnTaskId =
                UUID.randomUUID();

        TaskDependency expected =
                mock(TaskDependency.class);

        when(graphLock.runExclusive(
                eq(eventTenantId),
                any(Supplier.class)))
                .thenReturn(expected);

        service.addDependencyCreatingMissing(
                taskId,
                dependsOnTaskId,
                1.0,
                eventTenantId);

        verify(graphLock)
                .runExclusive(
                        eq(eventTenantId),
                        any(Supplier.class));

        verify(taskRepository, never())
                .findById(taskId);
    }

    @Test
    void addDependency_missingTask_usesNullLockAndThrowsTaskNotFoundException() {

        UUID taskId =
                UUID.randomUUID();

        UUID dependsOnTaskId =
                UUID.randomUUID();

        when(taskRepository.findById(taskId))
                .thenReturn(Optional.empty());

        when(taskDependencyRepository.existsDependency(
                taskId,
                dependsOnTaskId))
                .thenReturn(false);

        when(taskDependencyRepository
                .findByTaskIdUnderExternalLock(
                        dependsOnTaskId))
                .thenReturn(List.of());

        when(taskCounterUpdater.requireTask(taskId))
                .thenThrow(
                        new TaskNotFoundException(taskId));

        when(graphLock.runExclusive(
                eq(null),
                any(Supplier.class)))
                .thenAnswer(invocation -> {

                    Supplier<?> action =
                            invocation.getArgument(1);

                    return action.get();
                });

        assertThatThrownBy(
                () -> service.addDependency(
                        taskId,
                        dependsOnTaskId))
                .isInstanceOf(
                        TaskNotFoundException.class);

        verify(graphLock)
                .runExclusive(
                        eq(null),
                        any(Supplier.class));
    }
}