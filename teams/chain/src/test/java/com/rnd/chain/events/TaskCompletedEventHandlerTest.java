package com.rnd.chain.events;

import com.rnd.chain.testsupport.TaskStateHistoryFixtures;

import com.rnd.chain.testsupport.PgRepos;
import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.repository.ConstraintRepository;
import com.rnd.chain.repository.GraphLock;
import com.rnd.chain.repository.TaskDependencyRepository;
import com.rnd.chain.repository.TaskRepository;
import com.rnd.chain.events.SchedulingPublishListener;
import com.rnd.chain.service.SchedulingUpdateService;
import com.rnd.chain.service.PriorityScoreService;
import com.rnd.chain.service.TaskCounterUpdater;
import com.rnd.chain.service.TaskEligibilityService;
import com.rnd.inbox.event.TaskCompletedEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.record.TimestampType;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Ported from the in-memory version; runs against the real Postgres repositories (rolled back per test). */
@Tag("postgres")
@SpringBootTest
@Transactional
class TaskCompletedEventHandlerTest {

    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 1, 1, 12, 0, 0);

    private static ConsumerRecord<String, TaskCompletedEvent> record(String taskId, boolean isCompleted,
                                                                      LocalDateTime kafkaTime) {
        TaskCompletedEvent event = TaskCompletedEvent.newBuilder()
                .setTaskId(taskId)
                .setIsCompleted(isCompleted)
                .build();
        return new ConsumerRecord<>("task.completed", 0, 0L, kafkaTime.toInstant(ZoneOffset.UTC).toEpochMilli(),
                TimestampType.CREATE_TIME, 0, 0, taskId, event, new RecordHeaders(), Optional.empty());
    }

    private static Task task(UUID id, TaskStatus status, LocalDateTime updatedAt, int blockingCount) {
        return Task.builder()
                .id(id).status(status).tenantId(com.rnd.chain.testsupport.TaskFixtures.DEFAULT_TENANT_ID)
                .isReady(blockingCount == 0).blockingCount(blockingCount).openConstraintCount(0)
                .createdAt(BASE_TIME).updatedAt(updatedAt)
                .build();
    }

    private record Fixture(TaskRepository taskRepo, TaskCompletedEventHandler handler) {
    }

    private static Fixture newFixture(List<Task> tasks, List<TaskDependency> edges) {
        TaskRepository taskRepo = PgRepos.tasks(tasks);
        TaskDependencyRepository dependencyRepo = PgRepos.dependencies(edges);
        SchedulingUpdateService scheduling = mock(SchedulingUpdateService.class);
        TaskEligibilityService service = new TaskEligibilityService(taskRepo, dependencyRepo,
                PgRepos.graphLock(), new TaskCounterUpdater(taskRepo, dependencyRepo,
                        new PriorityScoreService(), new SchedulingPublishListener(taskRepo, scheduling)), TaskStateHistoryFixtures.newRecorder());
        ChainEventProcessingTemplate template = new ChainEventProcessingTemplate(PgRepos.processedEvents());
        return new Fixture(taskRepo, new TaskCompletedEventHandler(service, template));
    }

    private static Fixture blockerAndDependent(UUID blocker, UUID dependent) {
        return newFixture(
                List.of(task(blocker, TaskStatus.ACTIVE, BASE_TIME, 0),
                        task(dependent, TaskStatus.PENDING, BASE_TIME, 1)),
                List.of(new TaskDependency(dependent, blocker)));
    }

    @Test
    void onMessage_completingABlocker_releasesItsDependents() {
        UUID blocker = UUID.randomUUID();
        UUID dependent = UUID.randomUUID();
        Fixture fixture = blockerAndDependent(blocker, dependent);

        fixture.handler().onMessage(record(blocker.toString(), true, BASE_TIME.plusMinutes(5)));

        assertEquals(TaskStatus.DONE, fixture.taskRepo().findById(blocker).orElseThrow().getStatus());
        Task dependentAfter = fixture.taskRepo().findById(dependent).orElseThrow();
        assertEquals(0, dependentAfter.getBlockingCount());
        assertTrue(dependentAfter.isReady());
    }

    @Test
    void onMessage_doesNotOverwriteUpdatedAtWithTheKafkaTimestamp() {
        UUID blocker = UUID.randomUUID();
        Fixture fixture = newFixture(List.of(task(blocker, TaskStatus.ACTIVE, BASE_TIME, 0)), List.of());

        fixture.handler().onMessage(record(blocker.toString(), true, BASE_TIME.plusMinutes(5)));

        assertEquals(BASE_TIME, fixture.taskRepo().findById(blocker).orElseThrow().getUpdatedAt());
    }

    @Test
    void onMessage_redelivered_doesNotDecrementTwice() {
        UUID blocker = UUID.randomUUID();
        UUID dependent = UUID.randomUUID();
        UUID otherBlocker = UUID.randomUUID();
        Fixture fixture = newFixture(
                List.of(task(blocker, TaskStatus.ACTIVE, BASE_TIME, 0),
                        task(otherBlocker, TaskStatus.ACTIVE, BASE_TIME, 0),
                        task(dependent, TaskStatus.PENDING, BASE_TIME, 2)),
                List.of(new TaskDependency(dependent, blocker), new TaskDependency(dependent, otherBlocker)));

        fixture.handler().onMessage(record(blocker.toString(), true, BASE_TIME.plusMinutes(5)));
        fixture.handler().onMessage(record(blocker.toString(), true, BASE_TIME.plusMinutes(6)));

        Task dependentAfter = fixture.taskRepo().findById(dependent).orElseThrow();
        assertEquals(1, dependentAfter.getBlockingCount());
        assertFalse(dependentAfter.isReady());
    }

    @Test
    void onMessage_arrivingAfterTheTaskWasReopened_isIgnored() {
        UUID blocker = UUID.randomUUID();
        UUID dependent = UUID.randomUUID();
        // The reopen (task.updated) was stamped later than this completed record's Kafka time.
        Fixture fixture = newFixture(
                List.of(task(blocker, TaskStatus.ACTIVE, BASE_TIME.plusMinutes(10), 0),
                        task(dependent, TaskStatus.PENDING, BASE_TIME, 1)),
                List.of(new TaskDependency(dependent, blocker)));

        fixture.handler().onMessage(record(blocker.toString(), true, BASE_TIME.plusMinutes(5)));

        assertEquals(TaskStatus.ACTIVE, fixture.taskRepo().findById(blocker).orElseThrow().getStatus());
        assertEquals(1, fixture.taskRepo().findById(dependent).orElseThrow().getBlockingCount());
    }

    @Test
    void onMessage_withIsCompletedFalse_isIgnored() {
        UUID blocker = UUID.randomUUID();
        UUID dependent = UUID.randomUUID();
        Fixture fixture = blockerAndDependent(blocker, dependent);

        fixture.handler().onMessage(record(blocker.toString(), false, BASE_TIME.plusMinutes(5)));

        assertEquals(TaskStatus.ACTIVE, fixture.taskRepo().findById(blocker).orElseThrow().getStatus());
        assertEquals(1, fixture.taskRepo().findById(dependent).orElseThrow().getBlockingCount());
    }

    @Test
    void onMessage_referencingATaskThatDoesNotExist_throwsTransientProcessingException() {
        Fixture fixture = newFixture(List.of(), List.of());
        ConsumerRecord<String, TaskCompletedEvent> unknown =
                record(UUID.randomUUID().toString(), true, BASE_TIME);

        assertThrows(ChainTransientProcessingException.class, () -> fixture.handler().onMessage(unknown));
    }

    @Test
    void onMessage_withAnInvalidTaskId_throwsNonRetryableEventException() {
        Fixture fixture = newFixture(List.of(), List.of());
        ConsumerRecord<String, TaskCompletedEvent> invalid = record("not-a-uuid", true, BASE_TIME);

        assertThrows(NonRetryableEventException.class, () -> fixture.handler().onMessage(invalid));
    }
}
