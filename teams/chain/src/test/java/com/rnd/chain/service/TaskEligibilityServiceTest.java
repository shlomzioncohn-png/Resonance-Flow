package com.rnd.chain.service;

import com.rnd.chain.testsupport.TaskStateHistoryFixtures;

import com.rnd.chain.testsupport.PgRepos;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import com.rnd.chain.repository.TaskReadinessRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.dto.TaskTestFixtures;
import com.rnd.chain.events.SchedulingPublishListener;
import com.rnd.chain.events.SchedulingUpdatePublisher;
import com.rnd.chain.events.TaskSchedulingUpdatedEvent;
import com.rnd.chain.exception.InvalidDependencyException;
import com.rnd.chain.exception.InvalidRequestException;
import com.rnd.chain.exception.TaskNotFoundException;
import com.rnd.chain.exception.TenantReassignmentException;
import com.rnd.chain.repository.ConstraintRepository;
import com.rnd.chain.repository.GraphLock;
import com.rnd.chain.repository.TaskDependencyRepository;
import com.rnd.chain.repository.TaskRepository;
import com.rnd.chain.testsupport.TestTransactions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

/** Ported from the in-memory version; runs against the real Postgres repositories (rolled back per test). */
@Tag("postgres")
@SpringBootTest
@Transactional
class TaskEligibilityServiceTest {

    // compute() is pure, so the collaborators are not needed to derive expected scores.
    private static final PriorityScoreService PURE_SCORER = new PriorityScoreService(null, null, null, null, null);

    private TaskRepository taskRepository;
    private TaskDependencyRepository dependencyRepository;

    private RecordingSchedulingUpdatePublisher publisher;

    private TaskEligibilityService service;
    private DependencyEdgeService edgeService;
    @Autowired
    private TaskReadinessRepository taskReadinessRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        taskRepository = PgRepos.tasks(List.of());
        dependencyRepository = PgRepos.dependencies(List.of());

        publisher = new RecordingSchedulingUpdatePublisher();

        SchedulingUpdateService schedulingUpdateService =
            new SchedulingUpdateService(publisher);

        GraphLock lock =
            PgRepos.graphLock();

        PriorityScoreService priorityScoreService =
                new PriorityScoreService(
                            taskRepository,
                            dependencyRepository,
                            taskReadinessRepository,
                            schedulingUpdateService,
                            new SchedulingPublishListener(
                            taskRepository,
                            schedulingUpdateService));

        TaskStateHistoryRecorder historyRecorder =
            TaskStateHistoryFixtures.newRecorder();

        edgeService =
            new DependencyEdgeService(
                    taskRepository,
                    dependencyRepository,
                    lock,
                    priorityScoreService,
                    historyRecorder,
                    taskReadinessRepository);

        service =
            new TaskEligibilityService(
                    taskRepository,
                    dependencyRepository,
                    lock,
                    edgeService,
                    priorityScoreService,
                    historyRecorder,
                    taskReadinessRepository);

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

        TestTransactions.runCommitted(() -> service.onTaskStatusChanged(
                dependencyId,
                TaskStatus.DONE));

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

        TestTransactions.runCommitted(() -> service.onTaskStatusChanged(
                dependencyId,
                TaskStatus.ACTIVE));

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

        TestTransactions.runCommitted(() -> service.recalculateTaskEligibility(
                taskId));

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

    // --- tenant isolation (issue #580) ------------------------------------

    @Test
    void onTaskStatusChangedIfNewer_backfillsNullStoredTenant() {
        UUID taskId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        Fixture fixture = newFixture(
                List.of(TaskTestFixtures.defaultTask(taskId).isReady(true).tenantId(null).build()), List.of());

        fixture.service().onTaskStatusChangedIfNewer(taskId, TaskStatus.ACTIVE,
                LocalDateTime.now().plusMinutes(1), tenantId, null);

        assertEquals(tenantId, fixture.taskRepo().findById(taskId).orElseThrow().getTenantId());
    }

    @Test
    void onTaskStatusChangedIfNewer_matchingTenant_appliesNormally() {
        UUID taskId = UUID.randomUUID();
        Fixture fixture = newFixture(List.of(buildTask(taskId, 0, 0, true)), List.of());

        fixture.service().onTaskStatusChangedIfNewer(taskId, TaskStatus.DONE,
                LocalDateTime.now().plusMinutes(1), TaskTestFixtures.DEFAULT_TENANT_ID, null);

        assertEquals(TaskStatus.DONE, fixture.taskRepo().findById(taskId).orElseThrow().getStatus());
    }

    @Test
    void onTaskStatusChangedIfNewer_differentTenant_throwsTenantReassignmentException() {
        UUID taskId = UUID.randomUUID();
        Fixture fixture = newFixture(List.of(buildTask(taskId, 0, 0, true)), List.of());

        assertThrows(TenantReassignmentException.class, () -> fixture.service().onTaskStatusChangedIfNewer(
                taskId, TaskStatus.DONE, LocalDateTime.now().plusMinutes(1), TaskTestFixtures.OTHER_TENANT_ID, null));
        assertEquals(TaskStatus.ACTIVE, fixture.taskRepo().findById(taskId).orElseThrow().getStatus(),
                "the status change must not apply alongside the rejected tenant change");
    }

    @Test
    void onTaskStatusChangedIfNewer_differentTenant_throwsEvenOnStaleRedelivery() {
        // The tenant check must fire before the staleness short-circuit, not after - an older/
        // equal eventUpdatedAt would otherwise silently swallow a genuine upstream bug signal.
        UUID taskId = UUID.randomUUID();
        LocalDateTime createdAt = LocalDateTime.now();
        Task task = TaskTestFixtures.defaultTask(taskId).createdAt(createdAt).updatedAt(createdAt).build();
        Fixture fixture = newFixture(List.of(task), List.of());

        assertThrows(TenantReassignmentException.class, () -> fixture.service().onTaskStatusChangedIfNewer(
                taskId, TaskStatus.DONE, createdAt.minusMinutes(1), TaskTestFixtures.OTHER_TENANT_ID, null));
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
                .tenantId(TaskTestFixtures.DEFAULT_TENANT_ID)
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

    private record Fixture(TaskRepository taskRepo,
                            TaskDependencyRepository dependencyRepo,
                            ConstraintRepository constraintRepo,
                            RecordingSchedulingUpdatePublisher publisher,
                            TaskEligibilityService service,
                            DependencyEdgeService edgeService,
                            PriorityScoreService priorityScoreService) {
    }

    private Fixture newFixture(
        List<Task> tasks,
        List<TaskDependency> edges) {

        TaskRepository taskRepo =
            PgRepos.tasks(tasks);

        TaskDependencyRepository dependencyRepo =
            PgRepos.dependencies(edges);

        ConstraintRepository constraintRepo =
            PgRepos.constraints();

        RecordingSchedulingUpdatePublisher fixturePublisher =
            new RecordingSchedulingUpdatePublisher();

        SchedulingUpdateService scheduling =
            new SchedulingUpdateService(fixturePublisher);

        GraphLock lock =
            PgRepos.graphLock();

        PriorityScoreService priorityScoreService =
                new PriorityScoreService(
                            taskRepo,
                            dependencyRepo,
                            taskReadinessRepository,
                            scheduling,
                            new SchedulingPublishListener(
                            taskRepo,
                            scheduling));

        TaskStateHistoryRecorder historyRecorder =
            TaskStateHistoryFixtures.newRecorder();

        DependencyEdgeService edgeService =
            new DependencyEdgeService(
                    taskRepo,
                    dependencyRepo,
                    lock,
                    priorityScoreService,
                    historyRecorder,
                    taskReadinessRepository);

        TaskEligibilityService eligibilityService =
            new TaskEligibilityService(
                    taskRepo,
                    dependencyRepo,
                    lock,
                    edgeService,
                    priorityScoreService,
                    historyRecorder,
                    taskReadinessRepository);

        return new Fixture(
            taskRepo,
            dependencyRepo,
            constraintRepo,
            fixturePublisher,
            eligibilityService,
            edgeService,
            priorityScoreService);
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

    // Issue #445: priority_score, spanning PriorityScoreService/DependencyEdgeService after the
    // #649 split - exercised here via the Fixture, which wires the collaborators together. The
    // direct PriorityScoreService tests live in PriorityScoreServicePostgresTest.

    @Test
    void addDependencies_withMindConfidence_lowersPriorityScoreByBlockerConfidence() {
        UUID taskId = UUID.randomUUID();
        UUID blockerId = UUID.randomUUID();
        Fixture fixture = newFixture(
                List.of(buildTask(taskId, 0, 0, true), buildTask(blockerId, 0, 0, true)),
                List.of());
        fixture.priorityScoreService().onScoreProposed(taskId, 0.8, 1.0);
        Double beforeBlocker = fixture.taskRepo().findById(taskId).orElseThrow().getPriorityScore();

        fixture.edgeService().addDependencies(taskId, Map.of(blockerId, 0.9));

        Double afterBlocker = fixture.taskRepo().findById(taskId).orElseThrow().getPriorityScore();
        assertTrue(afterBlocker < beforeBlocker,
                "a 0.9-confidence blocker should lower priority_score, got before=" + beforeBlocker
                        + " after=" + afterBlocker);
    }

    @Test
    void addDependencies_republishingSameEdgeWithHigherConfidence_lowersPriorityScoreFurther() {
        // Mind's republish semantics (confirmed with Ayala 2026-10-07): a repeat delivery upserts
        // confidence on the existing edge, it does not replace the edge set.
        UUID taskId = UUID.randomUUID();
        UUID blockerId = UUID.randomUUID();
        Fixture fixture = newFixture(
                List.of(buildTask(taskId, 0, 0, true), buildTask(blockerId, 0, 0, true)),
                List.of());
        fixture.priorityScoreService().onScoreProposed(taskId, 0.8, 1.0);
        fixture.edgeService().addDependencies(taskId, Map.of(blockerId, 0.2));
        Double withLowConfidence = fixture.taskRepo().findById(taskId).orElseThrow().getPriorityScore();
        PgRepos.clearPersistenceContext(); // the native upsert changes confidence behind the JPA cache

        fixture.edgeService().addDependencies(taskId, Map.of(blockerId, 0.9));

        Double withHighConfidence = fixture.taskRepo().findById(taskId).orElseThrow().getPriorityScore();
        assertTrue(withHighConfidence < withLowConfidence);
        // Still exactly one edge - the republish upserted, it didn't duplicate.
        assertEquals(1, fixture.dependencyRepo().findByTaskId(taskId).size());
    }

    @Test
    void addDependencies_withMindConfidence_raisesDependentsPriorityScore() {
        UUID dependentId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        Fixture fixture = newFixture(
                List.of(buildTask(dependentId, 0, 0, true), buildTask(taskId, 0, 0, true)),
                List.of());
        fixture.priorityScoreService().onScoreProposed(taskId, 0.5, 1.0);
        Double beforeDependent = fixture.taskRepo().findById(taskId).orElseThrow().getPriorityScore();

        fixture.edgeService().addDependencies(dependentId, Map.of(taskId, 0.8));

        Double afterDependent = fixture.taskRepo().findById(taskId).orElseThrow().getPriorityScore();
        assertTrue(afterDependent > beforeDependent,
                "a task another task confidently depends on should rank higher, got before="
                        + beforeDependent + " after=" + afterDependent);
    }

    @Test
    void addDependencies_confidenceOutOfRange_throwsAndAddsNoEdge() {
        UUID taskId = UUID.randomUUID();
        UUID blockerId = UUID.randomUUID();
        Fixture fixture = newFixture(
                List.of(buildTask(taskId, 0, 0, true), buildTask(blockerId, 0, 0, true)),
                List.of());

        assertThrows(InvalidDependencyException.class,
                () -> fixture.edgeService().addDependencies(taskId, Map.of(blockerId, 1.5)));

        assertTrue(fixture.dependencyRepo().findByTaskId(taskId).isEmpty());
    }

    @Test
    void unresolvedBlockerCompleting_recomputesAndPublishesPriorityScoreOnce() {
        // "A task whose last blocker completes gets a new score and exactly one published event"
        // - #445 acceptance criterion.
        UUID taskId = UUID.randomUUID();
        UUID blockerId = UUID.randomUUID();
        Fixture fixture = newFixture(
                List.of(buildTask(taskId, 1, 0, false), buildTask(blockerId, 0, 0, true)),
                List.of(new TaskDependency(taskId, blockerId, 0.9)));
        fixture.priorityScoreService().onScoreProposed(taskId, 0.8, 1.0);
        fixture.publisher().clear();

        TestTransactions.runCommitted(() -> fixture.service().onTaskStatusChanged(blockerId, TaskStatus.DONE));

        Task task = fixture.taskRepo().findById(taskId).orElseThrow();
        assertTrue(task.isReady());
        assertEquals(PURE_SCORER.compute(0.8, 1.0, 0, 0, 0), task.getPriorityScore());
        assertEquals(1, fixture.publisher().getEvents().size());
    }
    @Test
    void duplicateStatusEvent_doesNotPublishOrRecordHistoryTwice() {

        UUID blockerId =
            UUID.randomUUID();

        UUID dependentId =
            UUID.randomUUID();

        LocalDateTime eventTime =
            LocalDateTime.now().plusMinutes(5);

        Fixture fixture =
            newFixture(
                    List.of(
                            buildTask(
                                    blockerId,
                                    0,
                                    0,
                                    true,
                                    TaskStatus.ACTIVE),

                            buildTask(
                                    dependentId,
                                    1,
                                    0,
                                    false,
                                    TaskStatus.ACTIVE)),
                    List.of(
                            new TaskDependency(
                                    dependentId,
                                    blockerId)));

        fixture.publisher().clear();

        TestTransactions.runCommitted(
            () -> fixture.service()
                    .onTaskStatusChangedIfNewer(
                            blockerId,
                            TaskStatus.DONE,
                            eventTime,
                            TaskTestFixtures.DEFAULT_TENANT_ID, null));

        assertEquals(
            1,
            fixture.publisher()
                    .getEvents()
                    .size(),
            "first event should publish exactly once");

        Integer historyAfterFirst =
            jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM task_state_history
                    WHERE task_id IN (?, ?)
                    """,
                    Integer.class,
                    blockerId,
                    dependentId);

    /*
     * Exact same event delivered again.
     * updatedAt is equal, so it must be treated as a duplicate/no-op.
     */
        TestTransactions.runCommitted(
            () -> fixture.service()
                    .onTaskStatusChangedIfNewer(
                            blockerId,
                            TaskStatus.DONE,
                            eventTime,
                            TaskTestFixtures.DEFAULT_TENANT_ID, null));

        assertEquals(
            1,
            fixture.publisher()
                    .getEvents()
                    .size(),
            "duplicate event must not publish task.scheduling.updated again");

        Integer historyAfterDuplicate =
            jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM task_state_history
                    WHERE task_id IN (?, ?)
                    """,
                    Integer.class,
                    blockerId,
                    dependentId);

        assertEquals(
            historyAfterFirst,
            historyAfterDuplicate,
            "duplicate event must not create another history row");
}

    @Test
    void noOpRecalculation_doesNotPublishAgain() {
        UUID taskId = UUID.randomUUID();
        Fixture fixture = newFixture(List.of(buildTask(taskId, 0, 0, true)), List.of());
        fixture.priorityScoreService().onScoreProposed(taskId, 0.8, 1.0);
        fixture.publisher().clear();

        fixture.service().recalculateTaskEligibility(taskId);

        assertTrue(fixture.publisher().getEvents().isEmpty());
    }
}
