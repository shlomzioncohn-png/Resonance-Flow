package com.rnd.chain.service;

import com.rnd.chain.dto.Constraint;
import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.repository.ConstraintRepository;
import com.rnd.chain.repository.InMemoryTaskDependencyRepository;
import com.rnd.chain.repository.InMemoryTaskRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskEligibilityServiceTest {

    private record Fixture(InMemoryTaskRepository taskRepo,
                            InMemoryTaskDependencyRepository dependencyRepo,
                            ConstraintRepository constraintRepo,
                            TaskEligibilityService service) {
    }

    private static Fixture newFixture(List<Task> tasks, List<TaskDependency> edges) {
        InMemoryTaskRepository taskRepo = new InMemoryTaskRepository(tasks);
        InMemoryTaskDependencyRepository dependencyRepo = new InMemoryTaskDependencyRepository(edges);
        ConstraintRepository constraintRepo = mock(ConstraintRepository.class);
        return new Fixture(taskRepo, dependencyRepo, constraintRepo,
                new TaskEligibilityService(taskRepo, dependencyRepo, constraintRepo));
    }

    private static Constraint buildConstraint(String constraintId, UUID taskId,
                                               Constraint.ConstraintStatus status) {
        LocalDateTime now = LocalDateTime.now();
        return new Constraint(constraintId, taskId, Constraint.ConstraintType.APPROVAL_NEEDED,
                status, null, "source-ref", now, now);
    }

    private static Task buildTask(UUID id, int blockingCount, int openConstraintCount, boolean isReady) {
        return buildTask(id, blockingCount, openConstraintCount, isReady, TaskStatus.ACTIVE);
    }

    private static Task buildTask(UUID id, int blockingCount, int openConstraintCount, boolean isReady,
                                   TaskStatus status) {
        return Task.builder()
                .id(id)
                .ownerId("owner")
                .status(status)
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
        Fixture fixture = newFixture(List.of(buildTask(taskId, 0, 0, false)), List.of());

        fixture.service().recalculateTaskEligibility(taskId);

        assertTrue(fixture.taskRepo().findById(taskId).orElseThrow().isReady());
    }

    @Test
    void hasBlocker_becomesNotReady() {
        UUID taskId = UUID.randomUUID();
        // isReady starts stale (true) so the blocker actually flips it, exercising the recompute.
        Fixture fixture = newFixture(List.of(buildTask(taskId, 1, 0, true)), List.of());

        fixture.service().recalculateTaskEligibility(taskId);

        assertFalse(fixture.taskRepo().findById(taskId).orElseThrow().isReady());
    }

    @Test
    void hasOpenConstraint_becomesNotReady() {
        UUID taskId = UUID.randomUUID();
        Fixture fixture = newFixture(List.of(buildTask(taskId, 0, 1, true)), List.of());

        fixture.service().recalculateTaskEligibility(taskId);

        assertFalse(fixture.taskRepo().findById(taskId).orElseThrow().isReady());
    }

    @Test
    void alreadyCorrectState_remainsReady() {
        UUID taskId = UUID.randomUUID();
        Fixture fixture = newFixture(List.of(buildTask(taskId, 0, 0, true)), List.of());

        fixture.service().recalculateTaskEligibility(taskId);

        assertTrue(fixture.taskRepo().findById(taskId).orElseThrow().isReady());
    }

    @Test
    void alreadyCorrectState_doesNotReplaceStoredTaskInstance() {
        // Regression test for the code review finding: recalculateTaskEligibility used to write
        // an unconditional new Task even when nothing had actually changed.
        UUID taskId = UUID.randomUUID();
        Fixture fixture = newFixture(List.of(buildTask(taskId, 0, 0, true)), List.of());
        Task before = fixture.taskRepo().findById(taskId).orElseThrow();

        fixture.service().recalculateTaskEligibility(taskId);

        assertSame(before, fixture.taskRepo().findById(taskId).orElseThrow(),
                "no-op recalculation must not write a new Task instance");
    }

    @Test
    void staleState_recalculation_replacesStoredTaskInstance() {
        UUID taskId = UUID.randomUUID();
        Fixture fixture = newFixture(List.of(buildTask(taskId, 1, 0, true)), List.of());
        Task before = fixture.taskRepo().findById(taskId).orElseThrow();

        fixture.service().recalculateTaskEligibility(taskId);

        assertNotSame(before, fixture.taskRepo().findById(taskId).orElseThrow(),
                "a real eligibility change must still write a new Task instance");
    }

    @Test
    void taskNotFound_throwsException() {
        UUID taskId = UUID.randomUUID();
        Fixture fixture = newFixture(List.of(), List.of());

        assertThrows(IllegalArgumentException.class,
                () -> fixture.service().recalculateTaskEligibility(taskId));
    }

    @Test
    void addDependency_incrementsBlockingCount_andStaysNotReady() {
        UUID taskId = UUID.randomUUID();
        UUID dependsOnTaskId = UUID.randomUUID();
        Fixture fixture = newFixture(
                List.of(buildTask(taskId, 0, 0, true), buildTask(dependsOnTaskId, 0, 0, true)), // ACTIVE, not DONE
                List.of());

        fixture.service().addDependency(taskId, dependsOnTaskId);

        assertTrue(fixture.dependencyRepo().findByTaskId(taskId)
                .contains(new TaskDependency(taskId, dependsOnTaskId)));
        Task task = fixture.taskRepo().findById(taskId).orElseThrow();
        assertEquals(1, task.getBlockingCount());
        assertFalse(task.isReady());
    }

    @Test
    void addDependency_duplicate_doesNotInflateBlockingCount() {
        UUID taskId = UUID.randomUUID();
        UUID dependsOnTaskId = UUID.randomUUID();
        // taskId's own Task is deliberately NOT seeded: for a duplicate edge, requireTask(taskId)
        // must never run, so if it did, this would throw instead of completing silently.
        Fixture fixture = newFixture(
                List.of(buildTask(dependsOnTaskId, 0, 0, true)),
                List.of(new TaskDependency(taskId, dependsOnTaskId)));

        assertDoesNotThrow(() -> fixture.service().addDependency(taskId, dependsOnTaskId));

        assertEquals(1, fixture.dependencyRepo().findByTaskId(taskId).size());
    }

    @Test
    void addDependency_targetAlreadyDone_doesNotIncrementBlockingCount_butStillRecalculates() {
        // Adding a dependency on an already-DONE task must not count against blockingCount -
        // there will be no future status transition to ever decrement it back off - but
        // eligibility must still be recalculated (e.g. this could be the task's first-ever
        // edge, and it started isReady=false by default with nothing else to fix that).
        UUID taskId = UUID.randomUUID();
        UUID dependsOnTaskId = UUID.randomUUID();
        Fixture fixture = newFixture(
                List.of(buildTask(taskId, 0, 0, false),
                        buildTask(dependsOnTaskId, 0, 0, true, TaskStatus.DONE)),
                List.of());

        fixture.service().addDependency(taskId, dependsOnTaskId);

        assertTrue(fixture.dependencyRepo().findByTaskId(taskId)
                .contains(new TaskDependency(taskId, dependsOnTaskId)));
        Task task = fixture.taskRepo().findById(taskId).orElseThrow();
        assertEquals(0, task.getBlockingCount());
        assertTrue(task.isReady());
    }

    @Test
    void addDependency_targetAlreadyDone_stateAlreadyCorrect_doesNotReplaceStoredTaskInstance() {
        // Regression test for the code review finding: adding a dependency on an already-DONE
        // task never changes blockingCount, so when eligibility was already correct beforehand,
        // no write should happen at all - it used to write unconditionally.
        UUID taskId = UUID.randomUUID();
        UUID dependsOnTaskId = UUID.randomUUID();
        Fixture fixture = newFixture(
                List.of(buildTask(taskId, 0, 0, true),
                        buildTask(dependsOnTaskId, 0, 0, true, TaskStatus.DONE)),
                List.of());
        Task before = fixture.taskRepo().findById(taskId).orElseThrow();

        fixture.service().addDependency(taskId, dependsOnTaskId);

        assertSame(before, fixture.taskRepo().findById(taskId).orElseThrow(),
                "adding a dependency on an already-DONE task must not write a new Task instance "
                        + "when eligibility was already correct");
    }

    @Test
    void addDependency_wouldCreateCycle_throwsAndLeavesStateUnchanged() {
        // A already depends on B (A -> B). Trying to add B -> A would close the cycle.
        // No Task is seeded at all: the cycle check only reads the dependency graph, so it must
        // throw before ever touching taskRepository - IllegalArgumentException ("Task not
        // found") instead of IllegalStateException would mean that guarantee broke.
        UUID taskA = UUID.randomUUID();
        UUID taskB = UUID.randomUUID();
        Fixture fixture = newFixture(List.of(), List.of(new TaskDependency(taskA, taskB)));

        assertThrows(IllegalStateException.class,
                () -> fixture.service().addDependency(taskB, taskA));

        assertEquals(1, fixture.dependencyRepo().findByTaskId(taskA).size());
        assertTrue(fixture.dependencyRepo().findByTaskId(taskB).isEmpty());
    }

    @Test
    void addDependency_taskNotFound_throwsAndLeavesStateUnchanged() {
        // Regression test for the code review finding: taskDependencyRepository.save() used to
        // run before taskId's existence was validated - so a non-existent taskId left a phantom
        // edge permanently persisted even though the method threw.
        UUID taskId = UUID.randomUUID();
        UUID dependsOnTaskId = UUID.randomUUID();
        Fixture fixture = newFixture(List.of(buildTask(dependsOnTaskId, 0, 0, true)), List.of());

        assertThrows(IllegalArgumentException.class,
                () -> fixture.service().addDependency(taskId, dependsOnTaskId));

        assertTrue(fixture.dependencyRepo().findByTaskId(taskId).isEmpty());
    }

    @Test
    void addDependency_dependsOnTaskNotFound_throwsAndLeavesStateUnchanged() {
        UUID taskId = UUID.randomUUID();
        UUID dependsOnTaskId = UUID.randomUUID();
        Fixture fixture = newFixture(List.of(), List.of());

        assertThrows(IllegalArgumentException.class,
                () -> fixture.service().addDependency(taskId, dependsOnTaskId));

        assertTrue(fixture.dependencyRepo().findByTaskId(taskId).isEmpty());
    }

    @Test
    void removeDependency_decrementsBlockingCount_becomesReady() {
        UUID taskId = UUID.randomUUID();
        UUID dependsOnTaskId = UUID.randomUUID();
        Fixture fixture = newFixture(
                List.of(buildTask(taskId, 1, 0, false), buildTask(dependsOnTaskId, 0, 0, true)),
                List.of(new TaskDependency(taskId, dependsOnTaskId)));

        fixture.service().removeDependency(taskId, dependsOnTaskId);

        assertTrue(fixture.dependencyRepo().findByTaskId(taskId).isEmpty());
        Task task = fixture.taskRepo().findById(taskId).orElseThrow();
        assertEquals(0, task.getBlockingCount());
        assertTrue(task.isReady());
    }

    @Test
    void removeDependency_targetAlreadyDone_doesNotDecrementBlockingCount() {
        // Mirrors addDependency: an edge to an already-DONE task never counted against
        // blockingCount, so removing it must not decrement.
        UUID taskId = UUID.randomUUID();
        UUID dependsOnTaskId = UUID.randomUUID();
        Fixture fixture = newFixture(
                List.of(buildTask(taskId, 0, 0, true),
                        buildTask(dependsOnTaskId, 0, 0, true, TaskStatus.DONE)),
                List.of(new TaskDependency(taskId, dependsOnTaskId)));

        fixture.service().removeDependency(taskId, dependsOnTaskId);

        assertEquals(0, fixture.taskRepo().findById(taskId).orElseThrow().getBlockingCount());
    }

    @Test
    void removeDependency_notFound_doesNotChangeCounter() {
        // taskId's own Task is deliberately NOT seeded - if requireTask(taskId) ran, this
        // would throw instead of completing silently.
        UUID taskId = UUID.randomUUID();
        UUID dependsOnTaskId = UUID.randomUUID();
        Fixture fixture = newFixture(List.of(), List.of());

        assertDoesNotThrow(() -> fixture.service().removeDependency(taskId, dependsOnTaskId));
    }

    @Test
    void removeDependency_taskNotFound_throwsAndLeavesStateUnchanged() {
        // Mirrors addDependency_taskNotFound_throwsAndLeavesStateUnchanged: taskId's existence
        // used to be validated only after delete() already removed the edge - so a real edge
        // could be permanently removed while the caller still saw an exception implying the
        // removal had failed.
        UUID taskId = UUID.randomUUID();
        UUID dependsOnTaskId = UUID.randomUUID();
        Fixture fixture = newFixture(
                List.of(buildTask(dependsOnTaskId, 0, 0, true)),
                List.of(new TaskDependency(taskId, dependsOnTaskId)));

        assertThrows(IllegalArgumentException.class,
                () -> fixture.service().removeDependency(taskId, dependsOnTaskId));

        assertEquals(1, fixture.dependencyRepo().findByTaskId(taskId).size());
    }

    @Test
    void removeDependency_blockingCountNeverGoesNegative() {
        UUID taskId = UUID.randomUUID();
        UUID dependsOnTaskId = UUID.randomUUID();
        Fixture fixture = newFixture(
                List.of(buildTask(taskId, 0, 0, true), buildTask(dependsOnTaskId, 0, 0, true)),
                List.of(new TaskDependency(taskId, dependsOnTaskId)));

        fixture.service().removeDependency(taskId, dependsOnTaskId);

        assertEquals(0, fixture.taskRepo().findById(taskId).orElseThrow().getBlockingCount());
    }

    @Test
    void completingBlocker_singleDependent_becomesReady() {
        UUID taskB = UUID.randomUUID();
        UUID taskA = UUID.randomUUID();
        Fixture fixture = newFixture(
                List.of(buildTask(taskB, 0, 0, true), buildTask(taskA, 1, 0, false)),
                List.of(new TaskDependency(taskA, taskB)));

        fixture.service().onTaskStatusChanged(taskB, TaskStatus.DONE);

        assertEquals(TaskStatus.DONE, fixture.taskRepo().findById(taskB).orElseThrow().getStatus());
        Task a = fixture.taskRepo().findById(taskA).orElseThrow();
        assertEquals(0, a.getBlockingCount());
        assertTrue(a.isReady());
    }

    @Test
    void completingBlocker_multipleDependents_allRecalculated() {
        UUID taskB = UUID.randomUUID();
        UUID taskA = UUID.randomUUID();
        UUID taskC = UUID.randomUUID();
        Fixture fixture = newFixture(
                List.of(buildTask(taskB, 0, 0, true), buildTask(taskA, 1, 0, false), buildTask(taskC, 1, 0, false)),
                List.of(new TaskDependency(taskA, taskB), new TaskDependency(taskC, taskB)));

        fixture.service().onTaskStatusChanged(taskB, TaskStatus.DONE);

        assertTrue(fixture.taskRepo().findById(taskA).orElseThrow().isReady());
        assertTrue(fixture.taskRepo().findById(taskC).orElseThrow().isReady());
    }

    @Test
    void reopeningDoneTask_restoresDependentsBlockingCount() {
        UUID taskB = UUID.randomUUID();
        UUID taskA = UUID.randomUUID();
        Fixture fixture = newFixture(
                List.of(buildTask(taskB, 0, 0, true, TaskStatus.DONE),
                        buildTask(taskA, 0, 0, true)), // A was ready because B was DONE
                List.of(new TaskDependency(taskA, taskB)));

        fixture.service().onTaskStatusChanged(taskB, TaskStatus.PENDING);

        assertEquals(TaskStatus.PENDING, fixture.taskRepo().findById(taskB).orElseThrow().getStatus());
        Task a = fixture.taskRepo().findById(taskA).orElseThrow();
        assertEquals(1, a.getBlockingCount());
        assertFalse(a.isReady());
    }

    @Test
    void statusChangeToNonDone_fromNonDone_doesNotTriggerDependentsRecalculation() {
        // taskA's blockingCount (5, an arbitrary non-zero value) must come back completely
        // untouched - proof that adjustDependentsBlockingCount never ran for this transition.
        UUID taskB = UUID.randomUUID();
        UUID taskA = UUID.randomUUID();
        Fixture fixture = newFixture(
                List.of(buildTask(taskB, 0, 0, true), buildTask(taskA, 5, 0, false)),
                List.of(new TaskDependency(taskA, taskB)));

        fixture.service().onTaskStatusChanged(taskB, TaskStatus.PENDING);

        assertEquals(TaskStatus.PENDING, fixture.taskRepo().findById(taskB).orElseThrow().getStatus());
        assertEquals(5, fixture.taskRepo().findById(taskA).orElseThrow().getBlockingCount());
    }

    @Test
    void onConstraintAdded_newConstraint_incrementsOpenConstraintCount_andStaysNotReady() {
        UUID taskId = UUID.randomUUID();
        Fixture fixture = newFixture(List.of(buildTask(taskId, 0, 0, true)), List.of());
        Constraint constraint = buildConstraint(UUID.randomUUID().toString(), taskId,
                Constraint.ConstraintStatus.PENDING);
        // save() reports whether Postgres actually inserted a new row - true here simulates a
        // genuinely new constraint (see ConstraintRepository's Javadoc for why this, not a bare
        // "did it succeed", is what tells the caller whether to touch the counter).
        when(fixture.constraintRepo().save(constraint)).thenReturn(true);

        fixture.service().onConstraintAdded(constraint);

        Task task = fixture.taskRepo().findById(taskId).orElseThrow();
        assertEquals(1, task.getOpenConstraintCount());
        assertFalse(task.isReady());
    }

    @Test
    void onConstraintAdded_taskDoesNotExist_throwsAndNeverSaves() {
        // taskId's own Task is deliberately NOT seeded - covers both a genuinely missing task
        // and a soft-deleted one, since TaskRepository#findById treats both as not found.
        UUID taskId = UUID.randomUUID();
        Fixture fixture = newFixture(List.of(), List.of());
        Constraint constraint = buildConstraint(UUID.randomUUID().toString(), taskId,
                Constraint.ConstraintStatus.PENDING);

        assertThrows(IllegalArgumentException.class, () -> fixture.service().onConstraintAdded(constraint));

        verify(fixture.constraintRepo(), never()).save(any());
    }

    @Test
    void onConstraintAdded_redeliveredConstraint_doesNotIncrementAgain() {
        UUID taskId = UUID.randomUUID();
        Fixture fixture = newFixture(List.of(buildTask(taskId, 0, 1, false)), List.of());
        Constraint constraint = buildConstraint(UUID.randomUUID().toString(), taskId,
                Constraint.ConstraintStatus.PENDING);
        // false = the row already existed (redelivery of the same "constraint added" event).
        when(fixture.constraintRepo().save(constraint)).thenReturn(false);

        fixture.service().onConstraintAdded(constraint);

        Task task = fixture.taskRepo().findById(taskId).orElseThrow();
        assertEquals(1, task.getOpenConstraintCount());
    }

    @Test
    void onConstraintStatusChanged_closingLastConstraint_becomesReady() {
        UUID taskId = UUID.randomUUID();
        String constraintId = UUID.randomUUID().toString();
        Fixture fixture = newFixture(List.of(buildTask(taskId, 0, 1, false)), List.of());
        // updateStatus() reports the REAL previous status from Postgres, not a caller-supplied
        // claim - PENDING here simulates the constraint genuinely having been open before this call.
        when(fixture.constraintRepo().updateStatus(
                eq(taskId), eq(constraintId), eq(Constraint.ConstraintStatus.SATISFIED), any(), any()))
                .thenReturn(Constraint.ConstraintStatus.PENDING);

        fixture.service().onConstraintStatusChanged(
                taskId, constraintId, Constraint.ConstraintStatus.SATISFIED, null);

        Task task = fixture.taskRepo().findById(taskId).orElseThrow();
        assertEquals(0, task.getOpenConstraintCount());
        assertTrue(task.isReady());
    }

    @Test
    void onConstraintStatusChanged_reopeningConstraint_becomesNotReadyAgain() {
        UUID taskId = UUID.randomUUID();
        String constraintId = UUID.randomUUID().toString();
        Fixture fixture = newFixture(List.of(buildTask(taskId, 0, 0, true)), List.of());
        when(fixture.constraintRepo().updateStatus(
                eq(taskId), eq(constraintId), eq(Constraint.ConstraintStatus.PENDING), any(), any()))
                .thenReturn(Constraint.ConstraintStatus.SATISFIED);

        fixture.service().onConstraintStatusChanged(
                taskId, constraintId, Constraint.ConstraintStatus.PENDING, null);

        Task task = fixture.taskRepo().findById(taskId).orElseThrow();
        assertEquals(1, task.getOpenConstraintCount());
        assertFalse(task.isReady());
    }

    @Test
    void onConstraintStatusChanged_nonOpenTransition_doesNothing() {
        // taskId's own Task is deliberately NOT seeded - if the method touched the repository
        // at all for this non-open-changing transition, this would throw.
        UUID taskId = UUID.randomUUID();
        String constraintId = UUID.randomUUID().toString();
        Fixture fixture = newFixture(List.of(), List.of());
        when(fixture.constraintRepo().updateStatus(
                eq(taskId), eq(constraintId), eq(Constraint.ConstraintStatus.WAIVED), any(), any()))
                .thenReturn(Constraint.ConstraintStatus.SATISFIED);

        assertDoesNotThrow(() -> fixture.service().onConstraintStatusChanged(
                taskId, constraintId, Constraint.ConstraintStatus.WAIVED, null));
    }

    @Test
    void onConstraintStatusChanged_redeliveredTransition_doesNotDoubleAdjust() {
        // The DB already reflects the target state (PENDING), meaning this exact transition was
        // already applied - a redelivered event, not trusted from a caller-supplied previousStatus.
        UUID taskId = UUID.randomUUID();
        String constraintId = UUID.randomUUID().toString();
        Fixture fixture = newFixture(List.of(buildTask(taskId, 0, 1, false)), List.of());
        when(fixture.constraintRepo().updateStatus(
                eq(taskId), eq(constraintId), eq(Constraint.ConstraintStatus.PENDING), any(), any()))
                .thenReturn(Constraint.ConstraintStatus.PENDING);

        fixture.service().onConstraintStatusChanged(
                taskId, constraintId, Constraint.ConstraintStatus.PENDING, null);

        Task task = fixture.taskRepo().findById(taskId).orElseThrow();
        assertEquals(1, task.getOpenConstraintCount());
    }

    @Test
    void onConstraintStatusChanged_constraintBelongsToAnotherTask_throwsAndLeavesCounterUntouched() {
        // The repository matches on constraintId AND taskId, so a mismatched pair matches no row and
        // throws before anything is written - Task-B's counter must not move.
        UUID wrongTaskId = UUID.randomUUID();
        String constraintId = UUID.randomUUID().toString();
        Fixture fixture = newFixture(List.of(buildTask(wrongTaskId, 0, 0, true)), List.of());
        when(fixture.constraintRepo().updateStatus(
                eq(wrongTaskId), eq(constraintId), eq(Constraint.ConstraintStatus.PENDING), any(), any()))
                .thenThrow(new IllegalStateException("No constraint " + constraintId + " on task " + wrongTaskId));

        assertThrows(IllegalStateException.class, () -> fixture.service().onConstraintStatusChanged(
                wrongTaskId, constraintId, Constraint.ConstraintStatus.PENDING, null));

        Task task = fixture.taskRepo().findById(wrongTaskId).orElseThrow();
        assertEquals(0, task.getOpenConstraintCount());
        assertTrue(task.isReady());
    }
}
