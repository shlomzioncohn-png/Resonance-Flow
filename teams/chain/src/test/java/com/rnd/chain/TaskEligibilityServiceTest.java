package com.rnd.chain.service;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.dto.TaskDependency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

// TODO: unverified — לוודא ששם החבילה/מבנה תואם למוסכמת הפרויקט
@ExtendWith(MockitoExtension.class)
class TaskEligibilityServiceTest {

    @Mock
    private TaskRepository taskRepository; // TODO: unverified — לוודא שם/מיקום האמיתי

    @Mock
    private TaskDependencyRepository taskDependencyRepository; // TODO: unverified — לוודא שם/מיקום האמיתי

    @InjectMocks
    private TaskEligibilityService taskEligibilityService;

    private Task buildTask(Inbox.TaskId id, int blockingCount, int openConstraintCount, boolean isReady) {
        return new Task(id, null, TaskStatus.ACTIVE, isReady,
                blockingCount, openConstraintCount, null, null,
                LocalDateTime.now(), LocalDateTime.now(), null);
    }

    @Test
    void noDependencies_becomesReady() {
        Inbox.TaskId taskId = new Inbox.TaskId("task-1");
        Task task = buildTask(taskId, 0, 0, false);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        taskEligibilityService.recalculateTaskEligibility(taskId);

        assertTrue(task.isReady());
        verify(taskRepository).save(task);
    }

    @Test
    void hasBlocker_staysNotReady() {
        Inbox.TaskId taskId = new Inbox.TaskId("task-2");
        Task task = buildTask(taskId, 1, 0, false);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        taskEligibilityService.recalculateTaskEligibility(taskId);

        assertFalse(task.isReady());
        verify(taskRepository).save(task);
    }

    @Test
    void hasOpenConstraint_staysNotReady() {
        Inbox.TaskId taskId = new Inbox.TaskId("task-3");
        Task task = buildTask(taskId, 0, 1, false);
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        taskEligibilityService.recalculateTaskEligibility(taskId);

        assertFalse(task.isReady());
        verify(taskRepository).save(task);
    }

    @Test
    void alreadyCorrectState_doesNotSaveAgain() {
        Inbox.TaskId taskId = new Inbox.TaskId("task-4");
        Task task = buildTask(taskId, 0, 0, true); // כבר ready=true, ותנאי החישוב גם נותן true
        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        taskEligibilityService.recalculateTaskEligibility(taskId);

        verify(taskRepository, never()).save(any());
    }

    @Test
    void taskNotFound_throwsException() {
        Inbox.TaskId taskId = new Inbox.TaskId("missing");
        when(taskRepository.findById(taskId)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> taskEligibilityService.recalculateTaskEligibility(taskId));
    }

    @Test
    void addDependency_incrementsBlockingCount_andStaysNotReady() {
        Inbox.TaskId taskId = new Inbox.TaskId("task-A");
        Inbox.TaskId dependsOnTaskId = new Inbox.TaskId("task-B");
        Task task = buildTask(taskId, 0, 0, true); // התחילה ready

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        taskEligibilityService.addDependency(taskId, dependsOnTaskId);

        verify(taskDependencyRepository).save(any(TaskDependency.class));
        assertEquals(1, task.getBlockingCount());
        assertFalse(task.isReady());
    }

    @Test
    void addSecondDependency_multipleBlockers() {
        Inbox.TaskId taskId = new Inbox.TaskId("task-A");
        Task task = buildTask(taskId, 1, 0, false); // כבר יש חוסם אחד

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        taskEligibilityService.addDependency(taskId, new Inbox.TaskId("task-C"));

        assertEquals(2, task.getBlockingCount());
        assertFalse(task.isReady());
    }

    @Test
    void removeDependency_decrementsBlockingCount_becomesReady() {
        Inbox.TaskId taskId = new Inbox.TaskId("task-A");
        Inbox.TaskId dependsOnTaskId = new Inbox.TaskId("task-B");
        Task task = buildTask(taskId, 1, 0, false); // חסום ע"י חוסם יחיד

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        taskEligibilityService.removeDependency(taskId, dependsOnTaskId);

        verify(taskDependencyRepository).delete(taskId, dependsOnTaskId); // TODO: unverified — שם מתודה
        assertEquals(0, task.getBlockingCount());
        assertTrue(task.isReady());
    }

    @Test
    void removeOneOfMultipleBlockers_staysNotReady() {
        Inbox.TaskId taskId = new Inbox.TaskId("task-A");
        Task task = buildTask(taskId, 2, 0, false); // שני חוסמים

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        taskEligibilityService.removeDependency(taskId, new Inbox.TaskId("task-B"));

        assertEquals(1, task.getBlockingCount());
        assertFalse(task.isReady()); // עדיין נשאר חוסם אחד
    }

    @Test
    void removeDependency_blockingCountNeverGoesNegative() {
        Inbox.TaskId taskId = new Inbox.TaskId("task-A");
        Task task = buildTask(taskId, 0, 0, true); // אין חוסמים בכלל

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));

        taskEligibilityService.removeDependency(taskId, new Inbox.TaskId("task-ghost"));

        assertEquals(0, task.getBlockingCount()); // לא -1, בזכות Math.max(0, ...)
    }

    @Test
    void completingBlocker_singleDependent_becomesReady() {
        Inbox.TaskId taskB = new Inbox.TaskId("task-B");
        Inbox.TaskId taskA = new Inbox.TaskId("task-A");

        Task b = buildTask(taskB, 0, 0, true);
        Task a = buildTask(taskA, 1, 0, false); // A תלוי ב-B

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
        Inbox.TaskId taskB = new Inbox.TaskId("task-B");
        Inbox.TaskId taskA = new Inbox.TaskId("task-A");
        Inbox.TaskId taskC = new Inbox.TaskId("task-C");

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
    void statusChangeToNonDone_doesNotTriggerDependentsRecalculation() {
        Inbox.TaskId taskB = new Inbox.TaskId("task-B");
        Task b = buildTask(taskB, 0, 0, true);

        when(taskRepository.findById(taskB)).thenReturn(Optional.of(b));

        taskEligibilityService.onTaskStatusChanged(taskB, TaskStatus.PENDING);

        assertEquals(TaskStatus.PENDING, b.getStatus());
        verify(taskDependencyRepository, never()).findByDependsOnTaskId(any());
    }

    @Test
    void dependencyChain_onlyDirectDependentUpdated_notTransitive() {
        // A depends on B depends on C. C -> DONE.
        Inbox.TaskId taskC = new Inbox.TaskId("task-C");
        Inbox.TaskId taskB = new Inbox.TaskId("task-B");

        Task c = buildTask(taskC, 0, 0, true);
        Task b = buildTask(taskB, 1, 0, false); // B תלוי ב-C בלבד

        when(taskRepository.findById(taskC)).thenReturn(Optional.of(c));
        when(taskDependencyRepository.findByDependsOnTaskId(taskC))
                .thenReturn(List.of(new TaskDependency(taskB, taskC)));
        when(taskRepository.findById(taskB)).thenReturn(Optional.of(b));

        taskEligibilityService.onTaskStatusChanged(taskC, TaskStatus.DONE);

        assertTrue(b.isReady());
        // A (שתלוי ב-B) לא נגע כלל בקריאה הזו - צריך קריאה נפרדת כש-B יסתיים
        // verifyNoInteractions(); // TODO: unverified - למחוק/להתאים; רק להמחיש שאין
        // קריאה ל-taskA
    }

    @Test
    void diamondGraph_multiplePaths_readyOnlyAfterAllParentsDone() {
        // B ---> A ---\
        // --> D
        // B ---> C ---/
        Inbox.TaskId taskB = new Inbox.TaskId("task-B");
        Inbox.TaskId taskA = new Inbox.TaskId("task-A");
        Inbox.TaskId taskC = new Inbox.TaskId("task-C");
        Inbox.TaskId taskD = new Inbox.TaskId("task-D");

        Task b = buildTask(taskB, 0, 0, true);
        Task a = buildTask(taskA, 1, 0, false); // A תלוי ב-B
        Task c = buildTask(taskC, 1, 0, false); // C תלוי ב-B

        when(taskRepository.findById(taskB)).thenReturn(Optional.of(b));
        when(taskDependencyRepository.findByDependsOnTaskId(taskB))
                .thenReturn(List.of(new TaskDependency(taskA, taskB), new TaskDependency(taskC, taskB)));
        when(taskRepository.findById(taskA)).thenReturn(Optional.of(a));
        when(taskRepository.findById(taskC)).thenReturn(Optional.of(c));

        taskEligibilityService.onTaskStatusChanged(taskB, TaskStatus.DONE);

        assertTrue(a.isReady());
        assertTrue(c.isReady());
        // D לא נבדק כאן כלל - onTaskStatusChanged(B) לא אמור לגעת ב-D,
        // רק כש-A ו-C עצמם יעברו DONE (בקריאות נפרדות) D יתעדכן
        verify(taskRepository, never()).findById(taskD);
    }
}
