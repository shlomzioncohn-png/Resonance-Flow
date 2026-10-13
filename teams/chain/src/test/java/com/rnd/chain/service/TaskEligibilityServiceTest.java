package com.rnd.chain.service;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.dto.TaskTestFixtures;
import com.rnd.chain.event.SchedulingUpdatePublisher;
import com.rnd.chain.event.TaskSchedulingUpdatedEvent;
import com.rnd.chain.exception.TaskNotFoundException;
import com.rnd.chain.repository.InMemoryConstraintRepository;
import com.rnd.chain.repository.InMemoryGraphLock;
import com.rnd.chain.repository.InMemoryTaskDependencyRepository;
import com.rnd.chain.repository.InMemoryTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class TaskEligibilityServiceTest {

    private InMemoryTaskRepository taskRepository;
    private InMemoryTaskDependencyRepository dependencyRepository;

    private RecordingSchedulingUpdatePublisher publisher;

    private TaskEligibilityService service;
    private DependencyEdgeService edgeService;

    @BeforeEach
    void setUp() {
        taskRepository = new InMemoryTaskRepository(List.of());
        dependencyRepository = new InMemoryTaskDependencyRepository(List.of());
        publisher = new RecordingSchedulingUpdatePublisher();
        SchedulingUpdateService schedulingUpdateService = new SchedulingUpdateService(publisher);
        InMemoryGraphLock lock = new InMemoryGraphLock();
        TaskCounterUpdater counterUpdater = new TaskCounterUpdater(taskRepository, schedulingUpdateService);
        service = new TaskEligibilityService(
                taskRepository, dependencyRepository, schedulingUpdateService, lock, counterUpdater);
        edgeService = new DependencyEdgeService(dependencyRepository, lock, counterUpdater);
    }

    @Test
    void onTaskStatusChanged_whenDependencyBecomesDone_shouldUnblockDependent() {

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

        edgeService.addDependency(
                taskId,
                dependencyId);

        publisher.clear();

        service.onTaskStatusChanged(
                dependencyId,
                TaskStatus.DONE);

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
    }

    @Test
    void onTaskStatusChanged_whenDependencyReopens_shouldBlockDependentAgain() {

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

        edgeService.addDependency(
                taskId,
                dependencyId);

        publisher.clear();

        service.onTaskStatusChanged(
                dependencyId,
                TaskStatus.ACTIVE);

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
    }

    @Test
    void getTaskEligibility_whenTaskDoesNotExist_shouldThrowTaskNotFoundException() {

        UUID missingTaskId =
                UUID.randomUUID();

        assertThrows(
                TaskNotFoundException.class,
                () -> service.getTaskEligibility(
                        missingTaskId));
    }

    @Test
    void recalculateTaskEligibility_shouldFixIncorrectReadyState() {

        UUID taskId = UUID.randomUUID();

        taskRepository.save(
                createTask(
                        taskId,
                        TaskStatus.ACTIVE,
                        true,
                        1,
                        0));

        service.recalculateTaskEligibility(
                taskId);

        Task updated =
                taskRepository.findById(taskId)
                        .orElseThrow();

        assertFalse(
                updated.isReady());

        assertEquals(
                1,
                updated.getBlockingCount());

        assertEquals(
                1,
                publisher.getEvents().size());
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

        assertThrows(TaskNotFoundException.class,
                () -> fixture.service().recalculateTaskEligibility(taskId));
    }

    @Test
    void recalculateTaskEligibility_nullTaskId_throwsNpe() {
        Fixture fixture = newFixture(List.of(), List.of());

        assertThrows(NullPointerException.class,
                () -> fixture.service().recalculateTaskEligibility(null));
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
                            TaskEligibilityService service) {
    }

    private static Fixture newFixture(List<Task> tasks, List<TaskDependency> edges) {
        InMemoryTaskRepository taskRepo = new InMemoryTaskRepository(tasks);
        InMemoryTaskDependencyRepository dependencyRepo = new InMemoryTaskDependencyRepository(edges);
        InMemoryConstraintRepository constraintRepo = new InMemoryConstraintRepository();
        SchedulingUpdateService scheduling = new SchedulingUpdateService(event -> {});
        TaskCounterUpdater counterUpdater = new TaskCounterUpdater(taskRepo, scheduling);
        return new Fixture(taskRepo, dependencyRepo, constraintRepo, new TaskEligibilityService(taskRepo, dependencyRepo, scheduling, new InMemoryGraphLock(), counterUpdater));
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
