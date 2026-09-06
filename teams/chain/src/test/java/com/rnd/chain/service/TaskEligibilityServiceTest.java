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
        return Task.builder()
                .id(id)
                .ownerId("owner")
                .status(TaskStatus.ACTIVE)
                .isReady(isReady)
                .blockingCount(blockingCount)
                .openConstraintCount(openConstraintCount)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
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
    void hasBlocker_becomesNotReady() {
        UUID taskId = UUID.randomUUID();
        // isReady starts stale (true) so the blocker actually flips it, exercising the save path.
        Task task = buildTask(taskId, 1, 0, true);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        taskEligibilityService.recalculateTaskEligibility(taskId);

        assertFalse(task.isReady());
        verify(taskRepository).save(task);
    }

    @Test
    void hasOpenConstraint_becomesNotReady() {
        UUID taskId = UUID.randomUUID();
        Task task = buildTask(taskId, 0, 1, true);
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
        Task dependsOnTask = buildTask(dependsOnTaskId, 0, 0, true); // ACTIVE, not DONE

        when(taskDependencyRepository.findByTaskId(dependsOnTaskId)).thenReturn(Collections.emptyList());
        when(taskDependencyRepository.findByTaskId(taskId)).thenReturn(Collections.emptyList());
        when(taskRepository.findById(dependsOnTaskId)).thenReturn(Optional.of(dependsOnTask));
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
        Task dependsOnTask = buildTask(dependsOnTaskId, 0, 0, true);

        when(taskDependencyRepository.findByTaskId(dependsOnTaskId)).thenReturn(Collections.emptyList());
        when(taskDependencyRepository.findByTaskId(taskId))
                .thenReturn(List.of(new TaskDependency(taskId, dependsOnTaskId)));
        when(taskRepository.findById(dependsOnTaskId)).thenReturn(Optional.of(dependsOnTask));

        taskEligibilityService.addDependency(taskId, dependsOnTaskId);

        verify(taskRepository, never()).findById(taskId);
    }

    @Test
    void addDependency_targetAlreadyDone_doesNotIncrementBlockingCount_butStillRecalculates() {
        // Adding a dependency on an already-DONE task must not count against blockingCount -
        // there will be no future status transition to ever decrement it back off - but
        // eligibility must still be recalculated (e.g. this could be the task's first-ever
        // edge, and it started isReady=false by default with nothing else to fix that).
        UUID taskId = UUID.randomUUID();
        UUID dependsOnTaskId = UUID.randomUUID();
        Task task = buildTask(taskId, 0, 0, false);
        Task dependsOnTask = buildTask(dependsOnTaskId, 0, 0, true);
        dependsOnTask.setStatus(TaskStatus.DONE);

        when(taskDependencyRepository.findByTaskId(dependsOnTaskId)).thenReturn(Collections.emptyList());
        when(taskDependencyRepository.findByTaskId(taskId)).thenReturn(Collections.emptyList());
        when(taskRepository.findById(dependsOnTaskId)).thenReturn(Optional.of(dependsOnTask));
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        taskEligibilityService.addDependency(taskId, dependsOnTaskId);

        verify(taskDependencyRepository).save(any(TaskDependency.class));
        assertEquals(0, task.getBlockingCount());
        assertTrue(task.isReady());
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
    void addDependency_taskNotFound_throwsAndLeavesStateUnchanged() {
        // Regression test for the code review finding: taskDependencyRepository.save() used to
        // run before taskId's existence was validated (only requireTask(taskId) inside
        // adjustBlockingCount checked it, which ran after save()) - so a non-existent taskId
        // left a phantom edge permanently persisted even though the method threw.
        UUID taskId = UUID.randomUUID();
        UUID dependsOnTaskId = UUID.randomUUID();
        Task dependsOnTask = buildTask(dependsOnTaskId, 0, 0, true);

        when(taskDependencyRepository.findByTaskId(dependsOnTaskId)).thenReturn(Collections.emptyList());
        when(taskDependencyRepository.findByTaskId(taskId)).thenReturn(Collections.emptyList());
        when(taskRepository.findById(dependsOnTaskId)).thenReturn(Optional.of(dependsOnTask));
        when(taskRepository.findById(taskId)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> taskEligibilityService.addDependency(taskId, dependsOnTaskId));

        verify(taskDependencyRepository, never()).save(any());
    }

    @Test
    void addDependency_dependsOnTaskNotFound_throwsAndLeavesStateUnchanged() {
        UUID taskId = UUID.randomUUID();
        UUID dependsOnTaskId = UUID.randomUUID();

        when(taskDependencyRepository.findByTaskId(dependsOnTaskId)).thenReturn(Collections.emptyList());
        when(taskRepository.findById(dependsOnTaskId)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> taskEligibilityService.addDependency(taskId, dependsOnTaskId));

        verify(taskDependencyRepository, never()).save(any());
    }

    @Test
    void removeDependency_decrementsBlockingCount_becomesReady() {
        UUID taskId = UUID.randomUUID();
        UUID dependsOnTaskId = UUID.randomUUID();
        Task task = buildTask(taskId, 1, 0, false);
        Task dependsOnTask = buildTask(dependsOnTaskId, 0, 0, true);

        when(taskDependencyRepository.findByTaskId(taskId))
                .thenReturn(List.of(new TaskDependency(taskId, dependsOnTaskId)));
        when(taskRepository.findById(dependsOnTaskId)).thenReturn(Optional.of(dependsOnTask));
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        taskEligibilityService.removeDependency(taskId, dependsOnTaskId);

        verify(taskDependencyRepository).delete(taskId, dependsOnTaskId);
        assertEquals(0, task.getBlockingCount());
        assertTrue(task.isReady());
    }

    @Test
    void removeDependency_targetAlreadyDone_doesNotDecrementBlockingCount() {
        // Mirrors addDependency: an edge to an already-DONE task never counted against
        // blockingCount, so removing it must not decrement.
        UUID taskId = UUID.randomUUID();
        UUID dependsOnTaskId = UUID.randomUUID();
        Task task = buildTask(taskId, 0, 0, true);
        Task dependsOnTask = buildTask(dependsOnTaskId, 0, 0, true);
        dependsOnTask.setStatus(TaskStatus.DONE);

        when(taskDependencyRepository.findByTaskId(taskId))
                .thenReturn(List.of(new TaskDependency(taskId, dependsOnTaskId)));
        when(taskRepository.findById(dependsOnTaskId)).thenReturn(Optional.of(dependsOnTask));
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        taskEligibilityService.removeDependency(taskId, dependsOnTaskId);

        verify(taskDependencyRepository).delete(taskId, dependsOnTaskId);
        assertEquals(0, task.getBlockingCount());
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
    void removeDependency_taskNotFound_throwsAndLeavesStateUnchanged() {
        // Mirrors addDependency_taskNotFound_throwsAndLeavesStateUnchanged: taskId's existence
        // used to be validated only inside adjustBlockingCount, which ran after delete() already
        // removed the edge - so a real edge could be permanently removed while the caller still
        // saw an exception implying the removal had failed.
        UUID taskId = UUID.randomUUID();
        UUID dependsOnTaskId = UUID.randomUUID();
        Task dependsOnTask = buildTask(dependsOnTaskId, 0, 0, true);

        when(taskDependencyRepository.findByTaskId(taskId))
                .thenReturn(List.of(new TaskDependency(taskId, dependsOnTaskId)));
        when(taskRepository.findById(dependsOnTaskId)).thenReturn(Optional.of(dependsOnTask));
        when(taskRepository.findById(taskId)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> taskEligibilityService.removeDependency(taskId, dependsOnTaskId));

        verify(taskDependencyRepository, never()).delete(any(), any());
    }

    @Test
    void removeDependency_blockingCountNeverGoesNegative() {
        UUID taskId = UUID.randomUUID();
        UUID dependsOnTaskId = UUID.randomUUID();
        Task task = buildTask(taskId, 0, 0, true);
        Task dependsOnTask = buildTask(dependsOnTaskId, 0, 0, true);

        when(taskDependencyRepository.findByTaskId(taskId))
                .thenReturn(List.of(new TaskDependency(taskId, dependsOnTaskId)));
        when(taskRepository.findById(dependsOnTaskId)).thenReturn(Optional.of(dependsOnTask));
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
