package com.rnd.chain.service;

import com.rnd.chain.dto.Constraint;
import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.repository.TaskDependencyRepository;
import com.rnd.chain.repository.TaskRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaskEligibilityServiceTest {

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private TaskDependencyRepository taskDependencyRepository;

    @InjectMocks
    private TaskEligibilityService taskEligibilityService;

    private Task buildTask(UUID id, int blockingCount, int openConstraintCount, boolean isReady) {
        return new Task(id, null, TaskStatus.ACTIVE, isReady,
                blockingCount, openConstraintCount, null, null,
                LocalDateTime.now(), LocalDateTime.now(), null);
    }

    @Test
    void noDependencies_becomesReady() {
        UUID taskId = UUID.randomUUID();
        Task task = buildTask(taskId, 0, 0, false);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        taskEligibilityService.recalculateTaskEligibility(taskId);

        assertTrue(task.isReady());
        verify(taskRepository).save(task);
    }

    @Test
    void hasBlocker_staysNotReady() {
        UUID taskId = UUID.randomUUID();
        Task task = buildTask(taskId, 1, 0, false);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        taskEligibilityService.recalculateTaskEligibility(taskId);

        assertFalse(task.isReady());
        verify(taskRepository).save(task);
    }

    @Test
    void hasOpenConstraint_staysNotReady() {
        UUID taskId = UUID.randomUUID();
        Task task = buildTask(taskId, 0, 1, false);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        taskEligibilityService.recalculateTaskEligibility(taskId);

        assertFalse(task.isReady());
        verify(taskRepository).save(task);
    }

    @Test
    void alreadyCorrectState_doesNotSaveAgain() {
        UUID taskId = UUID.randomUUID();
        Task task = buildTask(taskId, 0, 0, true);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        taskEligibilityService.recalculateTaskEligibility(taskId);

        verify(taskRepository, never()).save(any());
    }

    @Test
    void taskNotFound_throwsException() {
        UUID taskId = UUID.randomUUID();
        when(taskRepository.findById(taskId)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> taskEligibilityService.recalculateTaskEligibility(taskId));
    }

    @Test
    void addDependency_incrementsBlockingCount_andStaysNotReady() {
        UUID taskId = UUID.randomUUID();
        UUID dependsOnTaskId = UUID.randomUUID();
        Task task = buildTask(taskId, 0, 0, true);

        when(taskDependencyRepository.findByTaskId(dependsOnTaskId)).thenReturn(Collections.emptyList());
        when(taskDependencyRepository.findByTaskId(taskId)).thenReturn(Collections.emptyList());
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        taskEligibilityService.addDependency(taskId, dependsOnTaskId);

        verify(taskDependencyRepository).save(any(TaskDependency.class));
        assertEquals(1, task.getBlockingCount());
        assertFalse(task.isReady());
    }

    @Test
    void addDependency_duplicate_doesNotInflateBlockingCount() {
        UUID taskId = UUID.randomUUID();
        UUID dependsOnTaskId = UUID.randomUUID();

        when(taskDependencyRepository.findByTaskId(dependsOnTaskId)).thenReturn(Collections.emptyList());
        when(taskDependencyRepository.findByTaskId(taskId))
                .thenReturn(List.of(new TaskDependency(taskId, dependsOnTaskId)));

        taskEligibilityService.addDependency(taskId, dependsOnTaskId);

        verify(taskRepository, never()).findById(any());
    }

    @Test
    void addDependency_wouldCreateCycle_throwsAndLeavesStateUnchanged() {
        // A already depends on B (A -> B). Trying to add B -> A would close the cycle.
        UUID taskA = UUID.randomUUID();
        UUID taskB = UUID.randomUUID();

        when(taskDependencyRepository.findByTaskId(taskA))
                .thenReturn(List.of(new TaskDependency(taskA, taskB)));

        assertThrows(IllegalStateException.class,
                () -> taskEligibilityService.addDependency(taskB, taskA));

        verify(taskDependencyRepository, never()).save(any());
        verify(taskRepository, never()).findById(any());
    }

    @Test
    void removeDependency_decrementsBlockingCount_becomesReady() {
        UUID taskId = UUID.randomUUID();
        UUID dependsOnTaskId = UUID.randomUUID();
        Task task = buildTask(taskId, 1, 0, false);

        when(taskDependencyRepository.findByTaskId(taskId))
                .thenReturn(List.of(new TaskDependency(taskId, dependsOnTaskId)));
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        taskEligibilityService.removeDependency(taskId, dependsOnTaskId);

        verify(taskDependencyRepository).delete(taskId, dependsOnTaskId);
        assertEquals(0, task.getBlockingCount());
        assertTrue(task.isReady());
    }

    @Test
    void removeDependency_notFound_doesNotChangeCounter() {
        UUID taskId = UUID.randomUUID();
        UUID dependsOnTaskId = UUID.randomUUID();

        when(taskDependencyRepository.findByTaskId(taskId)).thenReturn(Collections.emptyList());

        taskEligibilityService.removeDependency(taskId, dependsOnTaskId);

        verify(taskRepository, never()).findById(any());
    }

    @Test
    void removeDependency_blockingCountNeverGoesNegative() {
        UUID taskId = UUID.randomUUID();
        UUID dependsOnTaskId = UUID.randomUUID();
        Task task = buildTask(taskId, 0, 0, true);

        when(taskDependencyRepository.findByTaskId(taskId))
                .thenReturn(List.of(new TaskDependency(taskId, dependsOnTaskId)));
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        taskEligibilityService.removeDependency(taskId, dependsOnTaskId);

        assertEquals(0, task.getBlockingCount());
    }

    @Test
    void completingBlocker_singleDependent_becomesReady() {
        UUID taskB = UUID.randomUUID();
        UUID taskA = UUID.randomUUID();

        Task b = buildTask(taskB, 0, 0, true);
        Task a = buildTask(taskA, 1, 0, false);

        when(taskRepository.findById(taskB)).thenReturn(Optional.of(b));
        when(taskDependencyRepository.findByDependsOnTaskId(taskB))
                .thenReturn(List.of(new TaskDependency(taskA, taskB)));
        when(taskRepository.findAllById(List.of(taskA))).thenReturn(List.of(a));

        taskEligibilityService.onTaskStatusChanged(taskB, TaskStatus.DONE);

        assertEquals(TaskStatus.DONE, b.getStatus());
        assertEquals(0, a.getBlockingCount());
        assertTrue(a.isReady());
    }

    @Test
    void completingBlocker_multipleDependents_allRecalculated() {
        UUID taskB = UUID.randomUUID();
        UUID taskA = UUID.randomUUID();
        UUID taskC = UUID.randomUUID();

        Task b = buildTask(taskB, 0, 0, true);
        Task a = buildTask(taskA, 1, 0, false);
        Task c = buildTask(taskC, 1, 0, false);

        when(taskRepository.findById(taskB)).thenReturn(Optional.of(b));
        when(taskDependencyRepository.findByDependsOnTaskId(taskB))
                .thenReturn(List.of(new TaskDependency(taskA, taskB), new TaskDependency(taskC, taskB)));
        when(taskRepository.findAllById(List.of(taskA, taskC))).thenReturn(List.of(a, c));

        taskEligibilityService.onTaskStatusChanged(taskB, TaskStatus.DONE);

        assertTrue(a.isReady());
        assertTrue(c.isReady());
    }

    @Test
    void reopeningDoneTask_restoresDependentsBlockingCount() {
        UUID taskB = UUID.randomUUID();
        UUID taskA = UUID.randomUUID();

        Task b = buildTask(taskB, 0, 0, true);
        b.setStatus(TaskStatus.DONE);
        Task a = buildTask(taskA, 0, 0, true); // A was ready because B was DONE

        when(taskRepository.findById(taskB)).thenReturn(Optional.of(b));
        when(taskDependencyRepository.findByDependsOnTaskId(taskB))
                .thenReturn(List.of(new TaskDependency(taskA, taskB)));
        when(taskRepository.findAllById(List.of(taskA))).thenReturn(List.of(a));

        taskEligibilityService.onTaskStatusChanged(taskB, TaskStatus.PENDING);

        assertEquals(TaskStatus.PENDING, b.getStatus());
        assertEquals(1, a.getBlockingCount());
        assertFalse(a.isReady());
    }

    @Test
    void statusChangeToNonDone_fromNonDone_doesNotTriggerDependentsRecalculation() {
        UUID taskB = UUID.randomUUID();
        Task b = buildTask(taskB, 0, 0, true);

        when(taskRepository.findById(taskB)).thenReturn(Optional.of(b));

        taskEligibilityService.onTaskStatusChanged(taskB, TaskStatus.PENDING);

        assertEquals(TaskStatus.PENDING, b.getStatus());
        verify(taskDependencyRepository, never()).findByDependsOnTaskId(any());
    }

    @Test
    void onConstraintAdded_incrementsOpenConstraintCount_andStaysNotReady() {
        UUID taskId = UUID.randomUUID();
        Task task = buildTask(taskId, 0, 0, true);

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        taskEligibilityService.onConstraintAdded(taskId);

        assertEquals(1, task.getOpenConstraintCount());
        assertFalse(task.isReady());
    }

    @Test
    void onConstraintStatusChanged_closingLastConstraint_becomesReady() {
        UUID taskId = UUID.randomUUID();
        Task task = buildTask(taskId, 0, 1, false);

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        taskEligibilityService.onConstraintStatusChanged(
                taskId, Constraint.ConstraintStatus.PENDING, Constraint.ConstraintStatus.SATISFIED);

        assertEquals(0, task.getOpenConstraintCount());
        assertTrue(task.isReady());
    }

    @Test
    void onConstraintStatusChanged_reopeningConstraint_becomesNotReadyAgain() {
        UUID taskId = UUID.randomUUID();
        Task task = buildTask(taskId, 0, 0, true);

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        taskEligibilityService.onConstraintStatusChanged(
                taskId, Constraint.ConstraintStatus.SATISFIED, Constraint.ConstraintStatus.PENDING);

        assertEquals(1, task.getOpenConstraintCount());
        assertFalse(task.isReady());
    }

    @Test
    void onConstraintStatusChanged_nonOpenTransition_doesNothing() {
        UUID taskId = UUID.randomUUID();

        taskEligibilityService.onConstraintStatusChanged(
                taskId, Constraint.ConstraintStatus.SATISFIED, Constraint.ConstraintStatus.WAIVED);

        verify(taskRepository, never()).findById(any());
    }
}
