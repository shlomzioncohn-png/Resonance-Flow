package com.rnd.chain.service;

import com.rnd.chain.dto.Constraint;
import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.repository.ConstraintRepository;
import com.rnd.chain.repository.TaskDependencyRepository;
import com.rnd.chain.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TaskStateReconciliationServiceTest {

    private TaskRepository taskRepository;
    private TaskDependencyRepository taskDependencyRepository;
    private ConstraintRepository constraintRepository;

    private TaskStateReconciliationService service;

    @BeforeEach
    void setUp() {
        taskRepository = Mockito.mock(TaskRepository.class);
        taskDependencyRepository = Mockito.mock(TaskDependencyRepository.class);
        constraintRepository = Mockito.mock(ConstraintRepository.class);

        service = new TaskStateReconciliationService(
                taskRepository,
                taskDependencyRepository,
                constraintRepository);
    }

    @Test
    void reconcileTaskState_whenStateIsAlreadyCorrect_shouldNotSave() {
        UUID taskId = UUID.randomUUID();

        Task task = createTask(
                taskId,
                true,
                0,
                0);

        when(taskRepository.findById(taskId))
                .thenReturn(Optional.of(task));

        when(taskDependencyRepository.findByTaskId(taskId))
                .thenReturn(Collections.emptyList());

        when(constraintRepository.findByTaskId(taskId))
                .thenReturn(Collections.emptyList());

        boolean changed = service.reconcileTaskState(taskId);

        assertFalse(changed);

        verify(taskRepository, never()).save(any(Task.class));
    }

    @Test
    void reconcileTaskState_whenBlockingCountIsWrong_shouldCorrectAndSave() {
        UUID taskId = UUID.randomUUID();
        UUID blockerId = UUID.randomUUID();

        Task task = createTask(
                taskId,
                true,
                0,
                0);

        Task blocker = createTask(
                blockerId,
                false,
                0,
                0);

        blocker.setStatus(TaskStatus.ACTIVE);

        TaskDependency dependency = new TaskDependency(taskId, blockerId);

        when(taskRepository.findById(taskId))
                .thenReturn(Optional.of(task));

        when(taskDependencyRepository.findByTaskId(taskId))
                .thenReturn(List.of(dependency));

        when(taskRepository.findAllById(List.of(blockerId)))
                .thenReturn(List.of(blocker));

        when(constraintRepository.findByTaskId(taskId))
                .thenReturn(Collections.emptyList());

        boolean changed = service.reconcileTaskState(taskId);

        assertTrue(changed);
        assertEquals(1, task.getBlockingCount());
        assertFalse(task.isReady());

        verify(taskRepository).save(task);
    }

    @Test
    void reconcileTaskState_whenOpenConstraintCountIsWrong_shouldCorrectAndSave() {
        UUID taskId = UUID.randomUUID();

        Task task = createTask(
                taskId,
                true,
                0,
                0);

        Constraint constraint = new Constraint(
                "constraint-1",
                taskId,
                Constraint.ConstraintType.APPROVAL_NEEDED,
                Constraint.ConstraintStatus.PENDING,
                Collections.emptyMap(),
                "test",
                LocalDateTime.now(),
                LocalDateTime.now());

        when(taskRepository.findById(taskId))
                .thenReturn(Optional.of(task));

        when(taskDependencyRepository.findByTaskId(taskId))
                .thenReturn(Collections.emptyList());

        when(constraintRepository.findByTaskId(taskId))
                .thenReturn(List.of(constraint));

        boolean changed = service.reconcileTaskState(taskId);

        assertTrue(changed);
        assertEquals(1, task.getOpenConstraintCount());
        assertFalse(task.isReady());

        verify(taskRepository).save(task);
    }

    private Task createTask(
            UUID id,
            boolean isReady,
            int blockingCount,
            int openConstraintCount) {
        return new Task(
                id,
                null,
                TaskStatus.ACTIVE,
                isReady,
                blockingCount,
                openConstraintCount,
                null,
                null,
                LocalDateTime.now(),
                LocalDateTime.now(),
                null);
    }

    @Test
    void reconcileTaskState_whenReadyFlagIsWrong_shouldCorrectAndSave() {
        UUID taskId = UUID.randomUUID();

        Task task = createTask(
                taskId,
                false,
                0,
                0);

        when(taskRepository.findById(taskId))
                .thenReturn(Optional.of(task));

        when(taskDependencyRepository.findByTaskId(taskId))
                .thenReturn(Collections.emptyList());

        when(constraintRepository.findByTaskId(taskId))
                .thenReturn(Collections.emptyList());

        boolean changed = service.reconcileTaskState(taskId);

        assertTrue(changed);
        assertTrue(task.isReady());

        verify(taskRepository).save(task);
    }

    @Test
    void reconcileTaskState_withMultipleBlockers_shouldCountOnlyBlockingTasks() {
        UUID taskId = UUID.randomUUID();
        UUID blocker1Id = UUID.randomUUID();
        UUID blocker2Id = UUID.randomUUID();
        UUID blocker3Id = UUID.randomUUID();

        Task task = createTask(taskId, true, 0, 0);

        Task blocker1 = createTask(blocker1Id, false, 0, 0);
        blocker1.setStatus(TaskStatus.ACTIVE);

        Task blocker2 = createTask(blocker2Id, false, 0, 0);
        blocker2.setStatus(TaskStatus.DONE);

        Task blocker3 = createTask(blocker3Id, false, 0, 0);
        blocker3.setStatus(TaskStatus.PENDING);

        List<TaskDependency> dependencies = List.of(
                new TaskDependency(taskId, blocker1Id),
                new TaskDependency(taskId, blocker2Id),
                new TaskDependency(taskId, blocker3Id));

        when(taskRepository.findById(taskId))
                .thenReturn(Optional.of(task));

        when(taskDependencyRepository.findByTaskId(taskId))
                .thenReturn(dependencies);

        when(taskRepository.findAllById(
                List.of(blocker1Id, blocker2Id, blocker3Id))).thenReturn(List.of(blocker1, blocker2, blocker3));

        when(constraintRepository.findByTaskId(taskId))
                .thenReturn(Collections.emptyList());

        boolean changed = service.reconcileTaskState(taskId);

        assertTrue(changed);
        assertEquals(2, task.getBlockingCount());
        assertFalse(task.isReady());

        verify(taskRepository).save(task);
    }

    @Test
    void reconcileTaskState_withMultipleConstraints_shouldCountOnlyPendingConstraints() {
        UUID taskId = UUID.randomUUID();

        Task task = createTask(taskId, true, 0, 0);

        Constraint pending = new Constraint(
                "constraint-1",
                taskId,
                Constraint.ConstraintType.APPROVAL_NEEDED,
                Constraint.ConstraintStatus.PENDING,
                Collections.emptyMap(),
                "test",
                LocalDateTime.now(),
                LocalDateTime.now());

        Constraint satisfied = new Constraint(
                "constraint-2",
                taskId,
                Constraint.ConstraintType.BLOCKED_BY_INFO,
                Constraint.ConstraintStatus.SATISFIED,
                Collections.emptyMap(),
                "test",
                LocalDateTime.now(),
                LocalDateTime.now());

        Constraint waived = new Constraint(
                "constraint-3",
                taskId,
                Constraint.ConstraintType.DATE_WINDOW,
                Constraint.ConstraintStatus.WAIVED,
                Collections.emptyMap(),
                "test",
                LocalDateTime.now(),
                LocalDateTime.now());

        when(taskRepository.findById(taskId))
                .thenReturn(Optional.of(task));

        when(taskDependencyRepository.findByTaskId(taskId))
                .thenReturn(Collections.emptyList());

        when(constraintRepository.findByTaskId(taskId))
                .thenReturn(List.of(pending, satisfied, waived));

        boolean changed = service.reconcileTaskState(taskId);

        assertTrue(changed);
        assertEquals(1, task.getOpenConstraintCount());
        assertFalse(task.isReady());

        verify(taskRepository).save(task);
    }

    @Test
    void reconcileTasks_whenOneTaskFails_shouldContinueWithOtherTasks() {
        UUID task1Id = UUID.randomUUID();
        UUID task2Id = UUID.randomUUID();
        UUID task3Id = UUID.randomUUID();

        Task task1 = createTask(task1Id, true, 0, 0);
        Task task3 = createTask(task3Id, true, 0, 0);

        when(taskRepository.findById(task1Id))
                .thenReturn(Optional.of(task1));

        when(taskRepository.findById(task2Id))
                .thenReturn(Optional.empty());

        when(taskRepository.findById(task3Id))
                .thenReturn(Optional.of(task3));

        when(taskDependencyRepository.findByTaskId(task1Id))
                .thenReturn(Collections.emptyList());

        when(taskDependencyRepository.findByTaskId(task3Id))
                .thenReturn(Collections.emptyList());

        when(constraintRepository.findByTaskId(task1Id))
                .thenReturn(Collections.emptyList());

        when(constraintRepository.findByTaskId(task3Id))
                .thenReturn(Collections.emptyList());

        service.reconcileTasks(List.of(
                task1Id,
                task2Id,
                task3Id));

        verify(taskRepository).findById(task1Id);
        verify(taskRepository).findById(task2Id);
        verify(taskRepository).findById(task3Id);
    }

    @Test
    void reconcileTaskState_withDependencyAndConstraint_shouldReconcileBoth() {
        UUID taskId = UUID.randomUUID();
        UUID blockerId = UUID.randomUUID();

        Task task = createTask(taskId, true, 0, 0);

        Task blocker = createTask(blockerId, false, 0, 0);
        blocker.setStatus(TaskStatus.ACTIVE);

        TaskDependency dependency = new TaskDependency(taskId, blockerId);

        Constraint constraint = new Constraint(
                "constraint-1",
                taskId,
                Constraint.ConstraintType.APPROVAL_NEEDED,
                Constraint.ConstraintStatus.PENDING,
                Collections.emptyMap(),
                "test",
                LocalDateTime.now(),
                LocalDateTime.now());

        when(taskRepository.findById(taskId))
                .thenReturn(Optional.of(task));

        when(taskDependencyRepository.findByTaskId(taskId))
                .thenReturn(List.of(dependency));

        when(taskRepository.findAllById(List.of(blockerId)))
                .thenReturn(List.of(blocker));

        when(constraintRepository.findByTaskId(taskId))
                .thenReturn(List.of(constraint));

        boolean changed = service.reconcileTaskState(taskId);

        assertTrue(changed);
        assertEquals(1, task.getBlockingCount());
        assertEquals(1, task.getOpenConstraintCount());
        assertFalse(task.isReady());

        verify(taskRepository).save(task);
    }

    @Test
    void reconcileTaskState_whenRunTwice_shouldBeIdempotent() {
        UUID taskId = UUID.randomUUID();
        UUID blockerId = UUID.randomUUID();

        Task task = createTask(taskId, true, 0, 0);

        Task blocker = createTask(blockerId, false, 0, 0);
        blocker.setStatus(TaskStatus.ACTIVE);

        TaskDependency dependency = new TaskDependency(taskId, blockerId);

        when(taskRepository.findById(taskId))
                .thenReturn(Optional.of(task));

        when(taskDependencyRepository.findByTaskId(taskId))
                .thenReturn(List.of(dependency));

        when(taskRepository.findAllById(List.of(blockerId)))
                .thenReturn(List.of(blocker));

        when(constraintRepository.findByTaskId(taskId))
                .thenReturn(Collections.emptyList());

        boolean firstRun = service.reconcileTaskState(taskId);
        boolean secondRun = service.reconcileTaskState(taskId);

        assertTrue(firstRun);
        assertFalse(secondRun);

        assertEquals(1, task.getBlockingCount());
        assertFalse(task.isReady());

        verify(taskRepository, times(1)).save(task);
    }
}