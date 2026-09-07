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
    void alreadyCorrectState_stillSavesEveryTime() {
        UUID taskId = UUID.randomUUID();
        Task task = buildTask(taskId, 0, 0, true);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        taskEligibilityService.recalculateTaskEligibility(taskId);

        verify(taskRepository).save(task);
    }

    @Test
    void taskNotFound_throwsException() {
        UUID taskId = UUID.randomUUID();
        when(taskRepository.findById(taskId)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> taskEligibilityService.recalculateTaskEligibility(taskId));
    }

    @Test
    void recalculateTaskEligibility_nullTaskId_throwsNpe() {
        assertThrows(NullPointerException.class,
                () -> taskEligibilityService.recalculateTaskEligibility(null));
    }

    @Test
    void addDependency_incrementsBlockingCount_andStaysNotReady() {
        UUID taskId = UUID.randomUUID();
        UUID dependsOnTaskId = UUID.randomUUID();
        Task task = buildTask(taskId, 0, 0, true);

        // findByTaskId(dependsOnTaskId) backs wouldCreateCycle's DFS (starts at dependsOnTaskId).
        // Finding #16: edgeExists now delegates to existsDependency, not findByTaskId(taskId) -
        // no stub needed for it here, since Mockito's boolean default (false) is already the
        // "not a duplicate" outcome this test wants.
        when(taskDependencyRepository.findByTaskId(dependsOnTaskId)).thenReturn(Collections.emptyList());
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.findById(dependsOnTaskId))
                .thenReturn(Optional.of(buildTask(dependsOnTaskId, 0, 0, true)));

        taskEligibilityService.addDependency(taskId, dependsOnTaskId);

        verify(taskDependencyRepository).save(any(TaskDependency.class));
        assertEquals(1, task.getBlockingCount());
        assertFalse(task.isReady());
    }

    @Test
    void addDependency_duplicate_doesNotInflateBlockingCount() {
        UUID taskId = UUID.randomUUID();
        UUID dependsOnTaskId = UUID.randomUUID();
        Task task = buildTask(taskId, 0, 0, true);
        Task dependsOnTask = buildTask(dependsOnTaskId, 0, 0, true);

        // Finding #1: both tasks are now validated to exist before the duplicate-edge check
        // runs, so both must be stubbed even though this call is expected to be a no-op.
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.findById(dependsOnTaskId)).thenReturn(Optional.of(dependsOnTask));
        when(taskDependencyRepository.findByTaskId(dependsOnTaskId)).thenReturn(Collections.emptyList());
        // Finding #16: edgeExists now delegates to existsDependency, not findByTaskId(taskId).
        when(taskDependencyRepository.existsDependency(taskId, dependsOnTaskId)).thenReturn(true);

        taskEligibilityService.addDependency(taskId, dependsOnTaskId);

        assertEquals(0, task.getBlockingCount());
        verify(taskRepository, never()).save(any());
    }

    @Test
    void addDependency_wouldCreateCycle_throwsAndLeavesStateUnchanged() {
        // A already depends on B (A -> B). Trying to add B -> A would close the cycle.
        UUID taskA = UUID.randomUUID();
        UUID taskB = UUID.randomUUID();

        // Finding #1: both tasks are now validated to exist before the cycle check runs.
        when(taskRepository.findById(taskA)).thenReturn(Optional.of(buildTask(taskA, 0, 0, true)));
        when(taskRepository.findById(taskB)).thenReturn(Optional.of(buildTask(taskB, 0, 0, true)));
        when(taskDependencyRepository.findByTaskId(taskA))
                .thenReturn(List.of(new TaskDependency(taskA, taskB)));

        assertThrows(IllegalStateException.class,
                () -> taskEligibilityService.addDependency(taskB, taskA));

        verify(taskDependencyRepository, never()).save(any());
    }

    @Test
    void addDependency_taskDoesNotExist_throwsAndNeverSaves() {
        UUID taskId = UUID.randomUUID();
        UUID dependsOnTaskId = UUID.randomUUID();

        when(taskRepository.findById(taskId)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> taskEligibilityService.addDependency(taskId, dependsOnTaskId));

        verify(taskDependencyRepository, never()).save(any());
    }

    @Test
    void addDependency_dependsOnTaskDoesNotExist_throwsAndNeverSaves() {
        UUID taskId = UUID.randomUUID();
        UUID dependsOnTaskId = UUID.randomUUID();
        Task task = buildTask(taskId, 0, 0, true);

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.findById(dependsOnTaskId)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> taskEligibilityService.addDependency(taskId, dependsOnTaskId));

        verify(taskDependencyRepository, never()).save(any());
    }

    @Test
    void addDependency_bothTasksDoNotExist_throwsAndNeverSaves() {
        UUID taskId = UUID.randomUUID();
        UUID dependsOnTaskId = UUID.randomUUID();

        when(taskRepository.findById(taskId)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> taskEligibilityService.addDependency(taskId, dependsOnTaskId));

        verify(taskDependencyRepository, never()).save(any());
        // taskId is validated first and fails fast - dependsOnTaskId is never even looked up.
        verify(taskRepository, never()).findById(dependsOnTaskId);
    }

    @Test
    void addDependency_nullTaskId_throwsNpe() {
        UUID dependsOnTaskId = UUID.randomUUID();
        assertThrows(NullPointerException.class,
                () -> taskEligibilityService.addDependency(null, dependsOnTaskId));
    }

    @Test
    void addDependency_nullDependsOnTaskId_throwsNpe() {
        UUID taskId = UUID.randomUUID();
        assertThrows(NullPointerException.class,
                () -> taskEligibilityService.addDependency(taskId, null));
    }

    @Test
    void removeDependency_decrementsBlockingCount_becomesReady() {
        UUID taskId = UUID.randomUUID();
        UUID dependsOnTaskId = UUID.randomUUID();
        Task task = buildTask(taskId, 1, 0, false);

        // Finding #16: edgeExists now delegates to existsDependency, not findByTaskId(taskId).
        when(taskDependencyRepository.existsDependency(taskId, dependsOnTaskId)).thenReturn(true);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.findById(dependsOnTaskId))
                .thenReturn(Optional.of(buildTask(dependsOnTaskId, 0, 0, true)));

        taskEligibilityService.removeDependency(taskId, dependsOnTaskId);

        verify(taskDependencyRepository).delete(taskId, dependsOnTaskId);
        assertEquals(0, task.getBlockingCount());
        assertTrue(task.isReady());
    }

    @Test
    void removeDependency_notFound_doesNotChangeCounter() {
        UUID taskId = UUID.randomUUID();
        UUID dependsOnTaskId = UUID.randomUUID();
        Task task = buildTask(taskId, 0, 0, true);

        // Finding #1: taskId is now validated to exist even for a no-op removal.
        // Finding #16: existsDependency defaults to false when unstubbed, which is already the
        // "no such edge" outcome this test wants - no explicit stub needed.
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        taskEligibilityService.removeDependency(taskId, dependsOnTaskId);

        assertEquals(0, task.getBlockingCount());
        verify(taskRepository, never()).save(any());
    }

    @Test
    void removeDependency_taskDoesNotExist_throwsAndNeverDeletes() {
        UUID taskId = UUID.randomUUID();
        UUID dependsOnTaskId = UUID.randomUUID();

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

        // Finding #16: edgeExists now delegates to existsDependency, not findByTaskId(taskId).
        // Must stub true, or the decrement branch (the thing this test exists to exercise) is
        // never reached at all.
        when(taskDependencyRepository.existsDependency(taskId, dependsOnTaskId)).thenReturn(true);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(taskRepository.findById(dependsOnTaskId))
                .thenReturn(Optional.of(buildTask(dependsOnTaskId, 0, 0, true)));

        taskEligibilityService.removeDependency(taskId, dependsOnTaskId);

        assertEquals(0, task.getBlockingCount());
    }

    @Test
    void removeDependency_nullTaskId_throwsNpe() {
        UUID dependsOnTaskId = UUID.randomUUID();
        assertThrows(NullPointerException.class,
                () -> taskEligibilityService.removeDependency(null, dependsOnTaskId));
    }

    @Test
    void removeDependency_nullDependsOnTaskId_throwsNpe() {
        UUID taskId = UUID.randomUUID();
        assertThrows(NullPointerException.class,
                () -> taskEligibilityService.removeDependency(taskId, null));
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
        when(taskRepository.findById(taskA)).thenReturn(Optional.of(a));

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
        when(taskRepository.findById(taskA)).thenReturn(Optional.of(a));
        when(taskRepository.findById(taskC)).thenReturn(Optional.of(c));

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
        when(taskRepository.findById(taskA)).thenReturn(Optional.of(a));

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
    void onTaskStatusChanged_nullTaskId_throwsNpe() {
        assertThrows(NullPointerException.class,
                () -> taskEligibilityService.onTaskStatusChanged(null, TaskStatus.DONE));
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
    void onConstraintAdded_nullTaskId_throwsNpe() {
        assertThrows(NullPointerException.class,
                () -> taskEligibilityService.onConstraintAdded(null));
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

    @Test
    void onConstraintStatusChanged_nullTaskId_throwsNpe() {
        assertThrows(NullPointerException.class,
                () -> taskEligibilityService.onConstraintStatusChanged(
                        null, Constraint.ConstraintStatus.PENDING, Constraint.ConstraintStatus.SATISFIED));
    }
}
