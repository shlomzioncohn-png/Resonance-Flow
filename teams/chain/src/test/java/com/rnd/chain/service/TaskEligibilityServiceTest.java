package com.rnd.chain.service;

import com.rnd.chain.dto.Constraint;
import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.dto.TaskTestFixtures;
import com.rnd.chain.repository.InMemoryTaskDependencyRepository;
import com.rnd.chain.repository.InMemoryTaskRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TaskEligibilityServiceTest {

    private record Fixture(InMemoryTaskRepository taskRepo,
                            InMemoryTaskDependencyRepository dependencyRepo,
                            TaskEligibilityService service) {
    }

    private static Fixture newFixture(List<Task> tasks, List<TaskDependency> edges) {
        InMemoryTaskRepository taskRepo = new InMemoryTaskRepository(tasks);
        InMemoryTaskDependencyRepository dependencyRepo = new InMemoryTaskDependencyRepository(edges);
        return new Fixture(taskRepo, dependencyRepo, new TaskEligibilityService(taskRepo, dependencyRepo));
    }

    private static Task buildTask(UUID id, int blockingCount, int openConstraintCount, boolean isReady) {
        return buildTask(id, blockingCount, openConstraintCount, isReady, TaskStatus.ACTIVE);
    }

    private static Task buildTask(UUID id, int blockingCount, int openConstraintCount, boolean isReady,
                                   TaskStatus status) {
        return TaskTestFixtures.defaultTask(id)
                .status(status)
                .isReady(isReady)
                .blockingCount(blockingCount)
                .openConstraintCount(openConstraintCount)
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
    void recalculateTaskEligibility_nullTaskId_throwsNpe() {
        Fixture fixture = newFixture(List.of(), List.of());

        assertThrows(NullPointerException.class,
                () -> fixture.service().recalculateTaskEligibility(null));
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
    void addSecondDependency_multipleBlockers() {
        // Restored regression scenario (review finding #9) - dropped during an earlier package
        // restructuring, originally at com.rnd.chain.TaskEligibilityServiceTest (commit a8148d6).
        UUID taskId = UUID.randomUUID();
        UUID dependsOnTaskId = UUID.randomUUID();
        Fixture fixture = newFixture(
                List.of(buildTask(taskId, 1, 0, false), // already has one blocker
                        buildTask(dependsOnTaskId, 0, 0, true)),
                List.of());

        fixture.service().addDependency(taskId, dependsOnTaskId);

        Task task = fixture.taskRepo().findById(taskId).orElseThrow();
        assertEquals(2, task.getBlockingCount());
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
    void addDependency_selfDependency_throwsIllegalStateAndNeverSaves() {
        // Review Finding #5: TaskDependency's own class comment documents that wouldCreateCycle
        // is the mechanism that rejects taskId == dependsOnTaskId, but no test exercised it.
        UUID taskId = UUID.randomUUID();
        Fixture fixture = newFixture(List.of(buildTask(taskId, 0, 0, true)), List.of());

        assertThrows(IllegalStateException.class,
                () -> fixture.service().addDependency(taskId, taskId));

        assertTrue(fixture.dependencyRepo().findByTaskId(taskId).isEmpty());
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
    void addDependency_nullTaskId_throwsNpe() {
        UUID dependsOnTaskId = UUID.randomUUID();
        Fixture fixture = newFixture(List.of(), List.of());

        assertThrows(NullPointerException.class,
                () -> fixture.service().addDependency(null, dependsOnTaskId));
    }

    @Test
    void addDependency_nullDependsOnTaskId_throwsNpe() {
        UUID taskId = UUID.randomUUID();
        Fixture fixture = newFixture(List.of(), List.of());

        assertThrows(NullPointerException.class,
                () -> fixture.service().addDependency(taskId, null));
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
    void removeOneOfMultipleBlockers_staysNotReady() {
        // Restored regression scenario (review finding #9) - see addSecondDependency_multipleBlockers.
        UUID taskId = UUID.randomUUID();
        UUID dependsOnTaskId = UUID.randomUUID();
        Fixture fixture = newFixture(
                List.of(buildTask(taskId, 2, 0, false), // two blockers
                        buildTask(dependsOnTaskId, 0, 0, true)),
                List.of(new TaskDependency(taskId, dependsOnTaskId)));

        fixture.service().removeDependency(taskId, dependsOnTaskId);

        assertTrue(fixture.dependencyRepo().findByTaskId(taskId).isEmpty());
        Task task = fixture.taskRepo().findById(taskId).orElseThrow();
        assertEquals(1, task.getBlockingCount());
        assertFalse(task.isReady());
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
    void removeDependency_nullTaskId_throwsNpe() {
        UUID dependsOnTaskId = UUID.randomUUID();
        Fixture fixture = newFixture(List.of(), List.of());

        assertThrows(NullPointerException.class,
                () -> fixture.service().removeDependency(null, dependsOnTaskId));
    }

    @Test
    void removeDependency_nullDependsOnTaskId_throwsNpe() {
        UUID taskId = UUID.randomUUID();
        Fixture fixture = newFixture(List.of(), List.of());

        assertThrows(NullPointerException.class,
                () -> fixture.service().removeDependency(taskId, null));
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
    void dependencyChain_onlyDirectDependentUpdated_notTransitive() {
        // Restored regression scenario (review finding #9) - see addSecondDependency_multipleBlockers.
        // A depends on B depends on C. C -> DONE should only update B; A needs its own event.
        UUID taskC = UUID.randomUUID();
        UUID taskB = UUID.randomUUID();
        UUID taskA = UUID.randomUUID();
        Fixture fixture = newFixture(
                List.of(buildTask(taskC, 0, 0, true),
                        buildTask(taskB, 1, 0, false), // B is blocked only by C
                        buildTask(taskA, 1, 0, false)), // A is blocked only by B
                List.of(new TaskDependency(taskB, taskC), new TaskDependency(taskA, taskB)));

        fixture.service().onTaskStatusChanged(taskC, TaskStatus.DONE);

        assertTrue(fixture.taskRepo().findById(taskB).orElseThrow().isReady());
        // A depends on B, not C - B becoming ready (not DONE) must not touch A's own counters:
        // the fan-out only reaches direct dependents of C, never transitively past them.
        assertEquals(1, fixture.taskRepo().findById(taskA).orElseThrow().getBlockingCount());
        assertFalse(fixture.taskRepo().findById(taskA).orElseThrow().isReady());
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
    void onTaskStatusChanged_taskWithNoOpenBlockersOrConstraints_staysReadyAfterStatusChange() {
        // Regression test (review finding): copyOf() previously didn't carry isReady across, so
        // Task.Builder's un-set primitive boolean silently defaulted to false - onTaskStatusChanged
        // corrupted isReady on every call, including a same-status no-op replay like this one,
        // regardless of the task's actual blockingCount/openConstraintCount.
        UUID taskId = UUID.randomUUID();
        Fixture fixture = newFixture(List.of(buildTask(taskId, 0, 0, true)), List.of());

        fixture.service().onTaskStatusChanged(taskId, TaskStatus.ACTIVE);

        assertTrue(fixture.taskRepo().findById(taskId).orElseThrow().isReady());
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
    void onTaskStatusChanged_nullTaskId_throwsNpe() {
        Fixture fixture = newFixture(List.of(), List.of());

        assertThrows(NullPointerException.class,
                () -> fixture.service().onTaskStatusChanged(null, TaskStatus.DONE));
    }

    @Test
    void onConstraintAdded_incrementsOpenConstraintCount_andStaysNotReady() {
        UUID taskId = UUID.randomUUID();
        UUID constraintId = UUID.randomUUID();
        Fixture fixture = newFixture(List.of(buildTask(taskId, 0, 0, true)), List.of());

        fixture.service().onConstraintAdded(taskId, constraintId);

        Task task = fixture.taskRepo().findById(taskId).orElseThrow();
        assertEquals(1, task.getOpenConstraintCount());
        assertFalse(task.isReady());
    }

    @Test
    void onConstraintAdded_sameConstraintIdTwice_isNoOpSecondTime() {
        UUID taskId = UUID.randomUUID();
        UUID constraintId = UUID.randomUUID();
        Fixture fixture = newFixture(List.of(buildTask(taskId, 0, 0, true)), List.of());

        fixture.service().onConstraintAdded(taskId, constraintId);
        fixture.service().onConstraintAdded(taskId, constraintId);

        assertEquals(1, fixture.taskRepo().findById(taskId).orElseThrow().getOpenConstraintCount());
    }

    @Test
    void onConstraintAdded_nullTaskId_throwsNpe() {
        Fixture fixture = newFixture(List.of(), List.of());

        assertThrows(NullPointerException.class,
                () -> fixture.service().onConstraintAdded(null, UUID.randomUUID()));
    }

    @Test
    void onConstraintAdded_nullConstraintId_throwsNpe() {
        Fixture fixture = newFixture(List.of(), List.of());

        assertThrows(NullPointerException.class,
                () -> fixture.service().onConstraintAdded(UUID.randomUUID(), null));
    }

    @Test
    void onConstraintAdded_taskDoesNotExist_throws() {
        // Review Finding #5: every other public method that resolves a task via requireTask has
        // a dedicated *_taskDoesNotExist_throws* test; this one didn't.
        UUID taskId = UUID.randomUUID();
        Fixture fixture = newFixture(List.of(), List.of());

        assertThrows(IllegalArgumentException.class,
                () -> fixture.service().onConstraintAdded(taskId, UUID.randomUUID()));
    }

    @Test
    void onConstraintStatusChanged_closingLastConstraint_becomesReady() {
        UUID taskId = UUID.randomUUID();
        UUID constraintId = UUID.randomUUID();
        Fixture fixture = newFixture(List.of(buildTask(taskId, 0, 0, false)), List.of());

        // Seed the tracked-open-constraints set (and the counter) via onConstraintAdded first,
        // so onConstraintStatusChanged's ground-truth check (PLAN.md Finding #10) recognizes
        // this constraintId as currently open and actually decrements on close.
        fixture.service().onConstraintAdded(taskId, constraintId);
        fixture.service().onConstraintStatusChanged(
                taskId, constraintId, Constraint.ConstraintStatus.PENDING, Constraint.ConstraintStatus.SATISFIED);

        Task task = fixture.taskRepo().findById(taskId).orElseThrow();
        assertEquals(0, task.getOpenConstraintCount());
        assertTrue(task.isReady());
    }

    @Test
    void onConstraintStatusChanged_reopeningConstraint_becomesNotReadyAgain() {
        UUID taskId = UUID.randomUUID();
        UUID constraintId = UUID.randomUUID();
        Fixture fixture = newFixture(List.of(buildTask(taskId, 0, 0, true)), List.of());

        fixture.service().onConstraintStatusChanged(
                taskId, constraintId, Constraint.ConstraintStatus.SATISFIED, Constraint.ConstraintStatus.PENDING);

        Task task = fixture.taskRepo().findById(taskId).orElseThrow();
        assertEquals(1, task.getOpenConstraintCount());
        assertFalse(task.isReady());
    }

    @Test
    void onConstraintStatusChanged_sameTransitionReplayed_isNoOpSecondTime() {
        UUID taskId = UUID.randomUUID();
        UUID constraintId = UUID.randomUUID();
        Fixture fixture = newFixture(List.of(buildTask(taskId, 0, 0, true)), List.of());

        fixture.service().onConstraintStatusChanged(
                taskId, constraintId, Constraint.ConstraintStatus.SATISFIED, Constraint.ConstraintStatus.PENDING);
        fixture.service().onConstraintStatusChanged(
                taskId, constraintId, Constraint.ConstraintStatus.SATISFIED, Constraint.ConstraintStatus.PENDING);

        assertEquals(1, fixture.taskRepo().findById(taskId).orElseThrow().getOpenConstraintCount());
    }

    @Test
    void onConstraintStatusChanged_nonOpenTransition_doesNothing() {
        // taskId's own Task is deliberately NOT seeded - if the method touched the repository
        // at all for this non-open-changing transition, this would throw.
        UUID taskId = UUID.randomUUID();
        Fixture fixture = newFixture(List.of(), List.of());

        assertDoesNotThrow(() -> fixture.service().onConstraintStatusChanged(
                taskId, UUID.randomUUID(), Constraint.ConstraintStatus.SATISFIED, Constraint.ConstraintStatus.WAIVED));
    }

    @Test
    void onConstraintStatusChanged_taskDoesNotExist_throws() {
        // Review Finding #5: every other public method that resolves a task via requireTask has
        // a dedicated *_taskDoesNotExist_throws* test; this one didn't.
        UUID taskId = UUID.randomUUID();
        Fixture fixture = newFixture(List.of(), List.of());

        assertThrows(IllegalArgumentException.class,
                () -> fixture.service().onConstraintStatusChanged(
                        taskId, UUID.randomUUID(), Constraint.ConstraintStatus.PENDING, Constraint.ConstraintStatus.SATISFIED));
    }

    @Test
    void onConstraintStatusChanged_nullTaskId_throwsNpe() {
        Fixture fixture = newFixture(List.of(), List.of());

        assertThrows(NullPointerException.class,
                () -> fixture.service().onConstraintStatusChanged(
                        null, UUID.randomUUID(), Constraint.ConstraintStatus.PENDING, Constraint.ConstraintStatus.SATISFIED));
    }

    @Test
    void onConstraintStatusChanged_nullConstraintId_throwsNpe() {
        UUID taskId = UUID.randomUUID();
        Fixture fixture = newFixture(List.of(), List.of());

        assertThrows(NullPointerException.class,
                () -> fixture.service().onConstraintStatusChanged(
                        taskId, null, Constraint.ConstraintStatus.PENDING, Constraint.ConstraintStatus.SATISFIED));
    }
}
