package com.rnd.chain.service;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.dto.TaskTestFixtures;
import com.rnd.chain.events.SchedulingPublishListener;
import com.rnd.chain.events.SchedulingUpdatePublisher;
import com.rnd.chain.events.TaskSchedulingUpdatedEvent;
import com.rnd.chain.exception.DependencyNotFoundException;
import com.rnd.chain.exception.DuplicateDependencyException;
import com.rnd.chain.exception.InvalidDependencyException;
import com.rnd.chain.exception.TaskNotFoundException;
import com.rnd.chain.repository.InMemoryConstraintRepository;
import com.rnd.chain.repository.InMemoryTaskDependencyRepository;
import com.rnd.chain.repository.InMemoryTaskRepository;
import com.rnd.chain.testsupport.TestTransactions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class DependencyEdgeServiceTest {

    private InMemoryTaskRepository taskRepository;
    private InMemoryTaskDependencyRepository dependencyRepository;

    private RecordingSchedulingUpdatePublisher publisher;

    private DependencyEdgeService service;

    @BeforeEach
    void setUp() {
        taskRepository = new InMemoryTaskRepository(List.of());
        dependencyRepository = new InMemoryTaskDependencyRepository(List.of());
        publisher = new RecordingSchedulingUpdatePublisher();
        SchedulingUpdateService schedulingUpdateService = new SchedulingUpdateService(publisher);
        DependencyGraphLock lock = new DependencyGraphLock();
        TaskCounterUpdater counterUpdater = new TaskCounterUpdater(taskRepository, dependencyRepository,
                new PriorityScoreService(), new SchedulingPublishListener(taskRepository, schedulingUpdateService));
        service = new DependencyEdgeService(dependencyRepository, lock, counterUpdater);
    }

    @Test
    void addDependency_shouldIncreaseBlockingCountAndMakeTaskNotReady() {

        UUID taskId = UUID.randomUUID();
        UUID dependencyId = UUID.randomUUID();

        taskRepository.save(
                createTask(
                        taskId,
                        TaskStatus.ACTIVE,
                        true,
                        0,
                        0));

        taskRepository.save(
                createTask(
                        dependencyId,
                        TaskStatus.ACTIVE,
                        true,
                        0,
                        0));

        TestTransactions.runCommitted(() -> service.addDependency(
                taskId,
                dependencyId));

        Task updated =
                taskRepository.findById(taskId)
                        .orElseThrow();

        assertEquals(
                1,
                updated.getBlockingCount());

        assertFalse(
                updated.isReady());

        assertEquals(
                1,
                publisher.getEvents().size());

        assertEquals(
                taskId,
                publisher.getEvents()
                        .get(0)
                        .getTaskId());

        assertFalse(
                publisher.getEvents()
                        .get(0)
                        .isAvailable());
    }

    @Test
    void addDependency_whenDependencyAlreadyDone_shouldNotBlockTask() {

        UUID taskId = UUID.randomUUID();
        UUID dependencyId = UUID.randomUUID();

        taskRepository.save(
                createTask(
                        taskId,
                        TaskStatus.ACTIVE,
                        true,
                        0,
                        0));

        taskRepository.save(
                createTask(
                        dependencyId,
                        TaskStatus.DONE,
                        true,
                        0,
                        0));

        service.addDependency(
                taskId,
                dependencyId);

        Task updated =
                taskRepository.findById(taskId)
                        .orElseThrow();

        assertEquals(
                0,
                updated.getBlockingCount());

        assertTrue(
                updated.isReady());

        assertTrue(
                publisher.getEvents().isEmpty());
    }

    @Test
    void addDependency_whenDuplicate_shouldThrowDuplicateDependencyException() {

        UUID taskId = UUID.randomUUID();
        UUID dependencyId = UUID.randomUUID();

        taskRepository.save(
                createTask(
                        taskId,
                        TaskStatus.ACTIVE,
                        true,
                        0,
                        0));

        taskRepository.save(
                createTask(
                        dependencyId,
                        TaskStatus.ACTIVE,
                        true,
                        0,
                        0));

        service.addDependency(
                taskId,
                dependencyId);

        assertThrows(
                DuplicateDependencyException.class,
                () -> service.addDependency(
                        taskId,
                        dependencyId));

        Task updated =
                taskRepository.findById(taskId)
                        .orElseThrow();

        assertEquals(
                1,
                updated.getBlockingCount());
    }

    @Test
    void addDependency_whenCycleWouldBeCreated_shouldThrowInvalidDependencyException() {

        UUID taskA = UUID.randomUUID();
        UUID taskB = UUID.randomUUID();
        UUID taskC = UUID.randomUUID();

        taskRepository.save(
                createTask(
                        taskA,
                        TaskStatus.ACTIVE,
                        true,
                        0,
                        0));

        taskRepository.save(
                createTask(
                        taskB,
                        TaskStatus.ACTIVE,
                        true,
                        0,
                        0));

        taskRepository.save(
                createTask(
                        taskC,
                        TaskStatus.ACTIVE,
                        true,
                        0,
                        0));

        service.addDependency(
                taskA,
                taskB);

        service.addDependency(
                taskB,
                taskC);

        assertThrows(
                InvalidDependencyException.class,
                () -> service.addDependency(
                        taskC,
                        taskA));

        assertTrue(
                dependencyRepository
                        .findByTaskId(taskC)
                        .isEmpty());
    }

    @Test
    void removeDependency_shouldDecreaseBlockingCountAndMakeTaskReady() {

        UUID taskId = UUID.randomUUID();
        UUID dependencyId = UUID.randomUUID();

        taskRepository.save(
                createTask(
                        taskId,
                        TaskStatus.ACTIVE,
                        true,
                        0,
                        0));

        taskRepository.save(
                createTask(
                        dependencyId,
                        TaskStatus.ACTIVE,
                        true,
                        0,
                        0));

        TestTransactions.runCommitted(() -> service.addDependency(
                taskId,
                dependencyId));

        publisher.clear();

        TestTransactions.runCommitted(() -> service.removeDependency(
                taskId,
                dependencyId));

        Task updated =
                taskRepository.findById(taskId)
                        .orElseThrow();

        assertEquals(
                0,
                updated.getBlockingCount());

        assertTrue(
                updated.isReady());

        assertEquals(
                1,
                publisher.getEvents().size());

        assertTrue(
                publisher.getEvents()
                        .get(0)
                        .isAvailable());
    }

    @Test
    void removeDependency_whenDependencyDoesNotExist_shouldThrow() {

        UUID taskId = UUID.randomUUID();
        UUID dependencyId = UUID.randomUUID();

        taskRepository.save(
                createTask(
                        taskId,
                        TaskStatus.ACTIVE,
                        true,
                        0,
                        0));

        taskRepository.save(
                createTask(
                        dependencyId,
                        TaskStatus.ACTIVE,
                        true,
                        0,
                        0));

        assertThrows(
                DependencyNotFoundException.class,
                () -> service.removeDependency(
                        taskId,
                        dependencyId));
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
        Fixture fixture = newFixture(
                List.of(buildTask(taskId, 1, 0, false),
                        buildTask(dependsOnTaskId, 0, 0, true)),
                List.of(new TaskDependency(taskId, dependsOnTaskId)));
        assertThrows(DuplicateDependencyException.class,
                () -> fixture.service().addDependency(taskId, dependsOnTaskId));
        assertEquals(1, fixture.dependencyRepo().findByTaskId(taskId).size());
        assertEquals(1, fixture.taskRepo().findById(taskId).orElseThrow().getBlockingCount());
    }

    @Test
    void addDependency_duplicate_targetTaskNoLongerExists_throwsDuplicate() {
        // A duplicate is reported as DuplicateDependencyException (the team's API contract) and is
        // checked before the target task is looked up, so a missing target never masks it.
        UUID taskId = UUID.randomUUID();
        UUID dependsOnTaskId = UUID.randomUUID();
        Fixture fixture = newFixture(
                List.of(),
                List.of(new TaskDependency(taskId, dependsOnTaskId)));

        assertThrows(DuplicateDependencyException.class,
                () -> fixture.service().addDependency(taskId, dependsOnTaskId));

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

        assertThrows(InvalidDependencyException.class,
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

        assertThrows(InvalidDependencyException.class,
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

        assertThrows(TaskNotFoundException.class,
                () -> fixture.service().addDependency(taskId, dependsOnTaskId));

        assertTrue(fixture.dependencyRepo().findByTaskId(taskId).isEmpty());
    }

    @Test
    void addDependency_dependsOnTaskNotFound_throwsAndLeavesStateUnchanged() {
        UUID taskId = UUID.randomUUID();
        UUID dependsOnTaskId = UUID.randomUUID();
        Fixture fixture = newFixture(List.of(), List.of());

        assertThrows(TaskNotFoundException.class,
                () -> fixture.service().addDependency(taskId, dependsOnTaskId));

        assertTrue(fixture.dependencyRepo().findByTaskId(taskId).isEmpty());
    }

    @Test
    void addDependencies_appliesEveryValidEntry() {
        UUID taskId = UUID.randomUUID();
        UUID depA = UUID.randomUUID();
        UUID depB = UUID.randomUUID();
        Fixture fixture = newFixture(
                List.of(buildTask(taskId, 0, 0, true), buildTask(depA, 0, 0, true), buildTask(depB, 0, 0, true)),
                List.of());

        fixture.service().addDependencies(taskId, List.of(depA, depB));

        assertEquals(2, fixture.dependencyRepo().findByTaskId(taskId).size());
        Task task = fixture.taskRepo().findById(taskId).orElseThrow();
        assertEquals(2, task.getBlockingCount());
        assertFalse(task.isReady());
    }

    @Test
    void addDependencies_oneCycleAmongValidEntries_appliesNoneOfThem() {
        // Regression test for the PR #467 review finding: A already depends on B (A -> B).
        // Proposing B depends on [C (valid), A (would close a cycle)] - the valid entry (C) comes
        // first in the list, so the old per-entry-applied-immediately code would have already
        // added it before ever reaching the cycle at the second entry.
        UUID taskA = UUID.randomUUID();
        UUID taskB = UUID.randomUUID();
        UUID taskC = UUID.randomUUID();
        Fixture fixture = newFixture(
                List.of(buildTask(taskB, 0, 0, true), buildTask(taskC, 0, 0, true)),
                List.of(new TaskDependency(taskA, taskB)));

        assertThrows(InvalidDependencyException.class,
                () -> fixture.service().addDependencies(taskB, List.of(taskC, taskA)));

        assertTrue(fixture.dependencyRepo().findByTaskId(taskB).isEmpty(),
                "the valid entry earlier in the batch must not be applied when a later entry fails");
        assertEquals(0, fixture.taskRepo().findById(taskB).orElseThrow().getBlockingCount());
    }

    @Test
    void addDependencies_oneMissingDependsOnTaskAmongValidEntries_appliesNoneOfThem() {
        // Same all-or-nothing guarantee, but for the "referenced task doesn't exist yet" failure
        // mode instead of a cycle.
        UUID taskId = UUID.randomUUID();
        UUID validDep = UUID.randomUUID();
        UUID missingDep = UUID.randomUUID();
        Fixture fixture = newFixture(
                List.of(buildTask(taskId, 0, 0, true), buildTask(validDep, 0, 0, true)),
                List.of());

        assertThrows(TaskNotFoundException.class,
                () -> fixture.service().addDependencies(taskId, List.of(validDep, missingDep)));

        assertTrue(fixture.dependencyRepo().findByTaskId(taskId).isEmpty(),
                "the valid entry earlier in the batch must not be applied when a later entry fails");
    }

    @Test
    void addDependencies_duplicateIdInSameBatch_countsOnce() {
        UUID taskId = UUID.randomUUID();
        UUID dependsOnTaskId = UUID.randomUUID();
        Fixture fixture = newFixture(
                List.of(buildTask(taskId, 0, 0, true), buildTask(dependsOnTaskId, 0, 0, true)),
                List.of());

        fixture.service().addDependencies(taskId, List.of(dependsOnTaskId, dependsOnTaskId));

        assertEquals(1, fixture.dependencyRepo().findByTaskId(taskId).size());
        assertEquals(1, fixture.taskRepo().findById(taskId).orElseThrow().getBlockingCount(),
                "a duplicate id within one batch must count as a single edge, not double the blockingCount");
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
        UUID taskId = UUID.randomUUID();
        UUID dependsOnTaskId = UUID.randomUUID();
        Fixture fixture = newFixture(
                List.of(buildTask(taskId, 0, 0, true),
                        buildTask(dependsOnTaskId, 0, 0, true)), List.of());
        assertThrows(DependencyNotFoundException.class,
                () -> fixture.service().removeDependency(taskId, dependsOnTaskId));
        assertTrue(fixture.dependencyRepo().findByTaskId(taskId).isEmpty());
        assertEquals(0, fixture.taskRepo().findById(taskId).orElseThrow().getBlockingCount());
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

        assertThrows(TaskNotFoundException.class,
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

    private Task createTask(
            UUID id,
            TaskStatus status,
            boolean ready,
            int blockingCount,
            int openConstraintCount) {

        LocalDateTime now =
                LocalDateTime.now();

        return Task.builder()
                .id(id)
                .parentTaskId(null)
                .ownerId("test-owner")
                .status(status)
                .isReady(ready)
                .blockingCount(blockingCount)
                .openConstraintCount(openConstraintCount)
                .estimatedDurationMin(null)
                .score(null)
                .createdAt(now)
                .updatedAt(now)
                .deletedAt(null)
                .build();
    }

    private static class RecordingSchedulingUpdatePublisher
            implements SchedulingUpdatePublisher {

        private final List<TaskSchedulingUpdatedEvent> events =
                new ArrayList<>();

        @Override
        public void publish(
                TaskSchedulingUpdatedEvent event) {

            events.add(event);
        }

        List<TaskSchedulingUpdatedEvent> getEvents() {
            return events;
        }

        void clear() {
            events.clear();
        }
    }

    private record Fixture(InMemoryTaskRepository taskRepo,
                            InMemoryTaskDependencyRepository dependencyRepo,
                            InMemoryConstraintRepository constraintRepo,
                            DependencyEdgeService service) {
    }

    private static Fixture newFixture(List<Task> tasks, List<TaskDependency> edges) {
        InMemoryTaskRepository taskRepo = new InMemoryTaskRepository(tasks);
        InMemoryTaskDependencyRepository dependencyRepo = new InMemoryTaskDependencyRepository(edges);
        InMemoryConstraintRepository constraintRepo = new InMemoryConstraintRepository();
        SchedulingUpdateService scheduling = new SchedulingUpdateService(event -> {});
        TaskCounterUpdater counterUpdater = new TaskCounterUpdater(taskRepo, dependencyRepo,
                new PriorityScoreService(), new SchedulingPublishListener(taskRepo, scheduling));
        return new Fixture(taskRepo, dependencyRepo, constraintRepo, new DependencyEdgeService(dependencyRepo, new DependencyGraphLock(), counterUpdater));
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
}
