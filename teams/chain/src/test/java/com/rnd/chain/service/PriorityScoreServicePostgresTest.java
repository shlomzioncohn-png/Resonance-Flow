package com.rnd.chain.service;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.dto.TaskTestFixtures;
import com.rnd.chain.dto.TaskTier;
import com.rnd.chain.events.SchedulingPublishListener;
import com.rnd.chain.events.SchedulingUpdatePublisher;
import com.rnd.chain.events.TaskSchedulingUpdatedEvent;
import com.rnd.chain.exception.InvalidRequestException;
import com.rnd.chain.exception.TaskNotFoundException;
import com.rnd.chain.exception.TenantReassignmentException;
import com.rnd.chain.repository.TaskReadinessRepository;
import com.rnd.chain.repository.TaskRepository;
import com.rnd.chain.testsupport.PgRepos;
import com.rnd.chain.testsupport.TestTransactions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code PriorityScoreService.onScoreProposed} and the AFTER_COMMIT publish it triggers
 * (commit / rollback / dedup / no transaction), against the real Postgres repositories.
 * Moved here from {@code TaskEligibilityServiceTest} together with the code (issue #917, PR 1).
 */
@Tag("postgres")
@SpringBootTest
@Transactional
class PriorityScoreServicePostgresTest {

    // compute() is pure, so the collaborators are not needed to derive expected scores.
    private static final PriorityScoreService PURE_SCORER = new PriorityScoreService(null, null, null, null, null);

    @Autowired
    private TaskReadinessRepository taskReadinessRepository;

    private Fixture newFixture(List<Task> tasks) {
        TaskRepository taskRepo = PgRepos.tasks(tasks);
        RecordingSchedulingUpdatePublisher publisher = new RecordingSchedulingUpdatePublisher();
        SchedulingUpdateService scheduling = new SchedulingUpdateService(publisher);
        PriorityScoreService priorityScoreService = new PriorityScoreService(
                taskRepo,
                PgRepos.dependencies(List.of()),
                taskReadinessRepository,
                scheduling,
                new SchedulingPublishListener(taskRepo, scheduling));
        return new Fixture(taskRepo, publisher, priorityScoreService);
    }

    private static Task buildTask(UUID id) {
        return TaskTestFixtures.defaultTask(id)
                .status(TaskStatus.ACTIVE)
                .isReady(true)
                .blockingCount(0)
                .openConstraintCount(0)
                .build();
    }

    private static class RecordingSchedulingUpdatePublisher implements SchedulingUpdatePublisher {

        private final List<TaskSchedulingUpdatedEvent> events = new ArrayList<>();

        @Override
        public void publish(TaskSchedulingUpdatedEvent event) {
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
                           RecordingSchedulingUpdatePublisher publisher,
                           PriorityScoreService priorityScoreService) {
    }

    @Test
    void onScoreProposed_setsScoreAndConfidence_andPublishesComputedPriorityScore() {
        UUID taskId = UUID.randomUUID();
        Fixture fixture = newFixture(List.of(buildTask(taskId)));

        TestTransactions.runCommitted(() -> fixture.priorityScoreService().onScoreProposed(taskId, 0.8, 0.5));

        Task task = fixture.taskRepo().findById(taskId).orElseThrow();
        assertEquals(0.8, task.getScore());
        assertEquals(0.5, task.getConfidence());

        Double expected = PURE_SCORER.compute(0.8, 0.5, 0, 0, 0);
        assertEquals(expected, task.getPriorityScore());

        List<TaskSchedulingUpdatedEvent> events = fixture.publisher().getEvents();
        assertEquals(1, events.size());
        assertEquals(expected, events.get(0).getPriorityScore());
    }

    @Test
    void onScoreProposed_withNullMindScore_clearsPriorityScoreAndPublishesTheClear() {
        UUID taskId = UUID.randomUUID();
        Fixture fixture = newFixture(List.of(buildTask(taskId)));
        fixture.priorityScoreService().onScoreProposed(taskId, 0.8, 0.5);
        fixture.publisher().clear();

        TestTransactions.runCommitted(() -> fixture.priorityScoreService().onScoreProposed(taskId, null, null));

        Task task = fixture.taskRepo().findById(taskId).orElseThrow();
        assertNull(task.getScore());
        assertNull(task.getConfidence());
        assertNull(task.getPriorityScore());

        List<TaskSchedulingUpdatedEvent> events = fixture.publisher().getEvents();
        assertEquals(1, events.size());
        assertNull(events.get(0).getPriorityScore());
    }

    @Test
    void onScoreProposed_mindScoreOutOfRange_throwsAndDoesNotPublish() {
        UUID taskId = UUID.randomUUID();
        Fixture fixture = newFixture(List.of(buildTask(taskId)));

        assertThrows(InvalidRequestException.class,
                () -> fixture.priorityScoreService().onScoreProposed(taskId, 1.5, 0.5));

        assertTrue(fixture.publisher().getEvents().isEmpty());
        assertNull(fixture.taskRepo().findById(taskId).orElseThrow().getScore());
    }

    @Test
    void onScoreProposed_unknownTask_throwsAndDoesNotPublish() {
        Fixture fixture = newFixture(List.of());

        assertThrows(TaskNotFoundException.class,
                () -> fixture.priorityScoreService().onScoreProposed(UUID.randomUUID(), 0.5, 0.5));

        assertTrue(fixture.publisher().getEvents().isEmpty());
    }

    @Test
    void rollback_neverPublishes() {
        UUID taskId = UUID.randomUUID();
        Fixture fixture = newFixture(List.of(buildTask(taskId)));

        TestTransactions.runRolledBack(() -> fixture.priorityScoreService().onScoreProposed(taskId, 0.8, 1.0));

        // The DB write itself is unconditional (issue #445 doesn't touch that) - only the
        // Kafka-facing publish is gated on commit.
        assertEquals(0.8, fixture.taskRepo().findById(taskId).orElseThrow().getScore());
        assertTrue(fixture.publisher().getEvents().isEmpty());
    }

    @Test
    void sameTaskChangedTwiceInOneTransaction_publishesExactlyOnce_withTheFinalState() {
        // Locked decision: "Deduplication: By taskId within the transaction."
        UUID taskId = UUID.randomUUID();
        Fixture fixture = newFixture(List.of(buildTask(taskId)));

        TestTransactions.runCommitted(() -> {
            fixture.priorityScoreService().onScoreProposed(taskId, 0.5, 1.0);
            fixture.priorityScoreService().onScoreProposed(taskId, 0.8, 1.0); // same task, changed again
        });

        List<TaskSchedulingUpdatedEvent> events = fixture.publisher().getEvents();
        assertEquals(1, events.size());
        assertEquals(PURE_SCORER.compute(0.8, 1.0, 0, 0, 0), events.get(0).getPriorityScore());
    }

    @Test
    void twoDifferentTasksChangedInOneTransaction_publishesOncePerTask() {
        UUID taskA = UUID.randomUUID();
        UUID taskB = UUID.randomUUID();
        Fixture fixture = newFixture(List.of(buildTask(taskA), buildTask(taskB)));

        TestTransactions.runCommitted(() -> {
            fixture.priorityScoreService().onScoreProposed(taskA, 0.5, 1.0);
            fixture.priorityScoreService().onScoreProposed(taskB, 0.6, 1.0);
        });

        List<TaskSchedulingUpdatedEvent> events = fixture.publisher().getEvents();
        assertEquals(2, events.size());
        assertEquals(
                Set.of(taskA, taskB),
                events.stream().map(TaskSchedulingUpdatedEvent::getTaskId).collect(Collectors.toSet()));
    }

    @Test
    void noActiveTransaction_doesNotPublish() {
        // fallbackExecution=false (locked decision): called with no transaction at all - the DB
        // write still happens, but nothing is published. Every production call site is
        // @Transactional (enforced by the guardrail test), so this only happens here, in a test
        // that deliberately skips TestTransactions.
        UUID taskId = UUID.randomUUID();
        Fixture fixture = newFixture(List.of(buildTask(taskId)));

        fixture.priorityScoreService().onScoreProposed(taskId, 0.8, 1.0);

        assertEquals(0.8, fixture.taskRepo().findById(taskId).orElseThrow().getScore());
        assertTrue(fixture.publisher().getEvents().isEmpty());
    }

    @Test
    void onScoreProposed_withTenant_createsPlaceholderForUnknownTaskAndStoresScoreTierAndScoredAt() {
        UUID taskId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        LocalDateTime scoredAt = LocalDateTime.of(2026, 10, 20, 12, 0);
        Fixture fixture = newFixture(List.of());

        TestTransactions.runCommitted(() -> fixture.priorityScoreService()
                .onScoreProposed(taskId, tenantId, TaskTier.TACTICAL, 0.8, 0.5, scoredAt));

        Task task = fixture.taskRepo().findById(taskId).orElseThrow();
        assertEquals(tenantId, task.getTenantId());
        assertEquals(TaskTier.TACTICAL, task.getTier());
        assertEquals(0.8, task.getScore());
        assertEquals(scoredAt, task.getScoredAt());
        assertNotNull(task.getPriorityScore());
    }

    @Test
    void onScoreProposed_withOlderScoredAt_isIgnored() {
        UUID taskId = UUID.randomUUID();
        UUID tenantId = TaskTestFixtures.DEFAULT_TENANT_ID;
        LocalDateTime scoredAt = LocalDateTime.of(2026, 10, 20, 12, 0);
        Fixture fixture = newFixture(List.of(buildTask(taskId)));
        TestTransactions.runCommitted(() -> fixture.priorityScoreService()
                .onScoreProposed(taskId, tenantId, TaskTier.TACTICAL, 0.8, 0.5, scoredAt));
        fixture.publisher().clear();

        TestTransactions.runCommitted(() -> fixture.priorityScoreService()
                .onScoreProposed(taskId, tenantId, TaskTier.STRATEGIC, 0.2, 0.9, scoredAt.minusMinutes(1)));

        Task task = fixture.taskRepo().findById(taskId).orElseThrow();
        assertEquals(0.8, task.getScore());
        assertEquals(0.5, task.getConfidence());
        assertEquals(TaskTier.TACTICAL, task.getTier());
        assertEquals(scoredAt, task.getScoredAt());
        assertTrue(fixture.publisher().getEvents().isEmpty());
    }

    @Test
    void onScoreProposed_withNewerScoredAt_replacesTheScore() {
        UUID taskId = UUID.randomUUID();
        UUID tenantId = TaskTestFixtures.DEFAULT_TENANT_ID;
        LocalDateTime scoredAt = LocalDateTime.of(2026, 10, 20, 12, 0);
        Fixture fixture = newFixture(List.of(buildTask(taskId)));
        TestTransactions.runCommitted(() -> fixture.priorityScoreService()
                .onScoreProposed(taskId, tenantId, null, 0.8, 0.5, scoredAt));

        TestTransactions.runCommitted(() -> fixture.priorityScoreService()
                .onScoreProposed(taskId, tenantId, null, 0.2, 0.9, scoredAt.plusMinutes(1)));

        Task task = fixture.taskRepo().findById(taskId).orElseThrow();
        assertEquals(0.2, task.getScore());
        assertEquals(scoredAt.plusMinutes(1), task.getScoredAt());
    }

    @Test
    void onScoreProposed_withTenantDifferentFromStored_throwsAndDoesNotChangeTheTask() {
        UUID taskId = UUID.randomUUID();
        Fixture fixture = newFixture(List.of(buildTask(taskId)));

        assertThrows(TenantReassignmentException.class, () -> fixture.priorityScoreService()
                .onScoreProposed(taskId, UUID.randomUUID(), null, 0.8, 0.5, null));

        assertNull(fixture.taskRepo().findById(taskId).orElseThrow().getScore());
    }
}
