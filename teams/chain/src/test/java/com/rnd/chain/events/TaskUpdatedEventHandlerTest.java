package com.rnd.chain.events;

import com.google.protobuf.Timestamp;
import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.repository.InMemoryConstraintRepository;
import com.rnd.chain.repository.InMemoryGraphLock;
import com.rnd.chain.service.SchedulingUpdateService;
import static org.mockito.Mockito.mock;
import com.rnd.chain.repository.InMemoryTaskDependencyRepository;
import com.rnd.chain.repository.InMemoryTaskRepository;
import com.rnd.chain.service.TaskCounterUpdater;
import com.rnd.chain.service.TaskEligibilityService;
import com.rnd.inbox.event.TaskUpdatedEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TaskUpdatedEventHandlerTest {

    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 1, 1, 12, 0, 0);
    private static final UUID TENANT_ID = UUID.randomUUID();

    private static Timestamp timestamp(LocalDateTime dateTime) {
        Instant instant = dateTime.toInstant(ZoneOffset.UTC);
        return Timestamp.newBuilder().setSeconds(instant.getEpochSecond()).setNanos(instant.getNano()).build();
    }

    private static Task buildTask(UUID id, TaskStatus status, LocalDateTime updatedAt) {
        return Task.builder()
                .id(id)
                .ownerId("owner")
                .tenantId(TENANT_ID)
                .status(status)
                .isReady(true)
                .blockingCount(0)
                .openConstraintCount(0)
                .createdAt(BASE_TIME)
                .updatedAt(updatedAt)
                .build();
    }

    private record Fixture(InMemoryTaskRepository taskRepo, TaskUpdatedEventHandler handler) {
    }

    private static Fixture newFixture(List<Task> tasks, List<TaskDependency> edges) {
        InMemoryTaskRepository taskRepo = new InMemoryTaskRepository(tasks);
        InMemoryTaskDependencyRepository dependencyRepo = new InMemoryTaskDependencyRepository(edges);
        SchedulingUpdateService scheduling = mock(SchedulingUpdateService.class);
        TaskEligibilityService eligibilityService = new TaskEligibilityService(taskRepo, dependencyRepo,
                scheduling, new InMemoryGraphLock(), new TaskCounterUpdater(taskRepo, scheduling));
        ChainEventProcessingTemplate template = new ChainEventProcessingTemplate(new InMemoryProcessedEventStore());
        return new Fixture(taskRepo, new TaskUpdatedEventHandler(eligibilityService, template));
    }

    @Test
    void onMessage_withANewerTimestamp_appliesTheStatusAndStampsUpdatedAt() {
        UUID taskId = UUID.randomUUID();
        Fixture fixture = newFixture(List.of(buildTask(taskId, TaskStatus.PENDING, BASE_TIME)), List.of());
        LocalDateTime newerTime = BASE_TIME.plusMinutes(10);

        fixture.handler().onMessage(TaskUpdatedEvent.newBuilder()
                .setTaskId(taskId.toString())
                .setTenantId(TENANT_ID.toString())
                .setStatus(com.rnd.inbox.event.TaskStatus.TASK_STATUS_IN_PROGRESS)
                .setUpdatedAt(timestamp(newerTime))
                .build());

        Task updated = fixture.taskRepo().findById(taskId).orElseThrow();
        assertEquals(TaskStatus.ACTIVE, updated.getStatus());
        assertEquals(newerTime, updated.getUpdatedAt());
    }

    @Test
    void onMessage_transitioningToDone_releasesDependents() {
        UUID blocker = UUID.randomUUID();
        UUID dependent = UUID.randomUUID();
        Fixture fixture = newFixture(
                List.of(buildTask(blocker, TaskStatus.ACTIVE, BASE_TIME),
                        Task.builder().id(dependent).ownerId("owner").tenantId(TENANT_ID).status(TaskStatus.PENDING)
                                .isReady(false).blockingCount(1).openConstraintCount(0)
                                .createdAt(BASE_TIME).updatedAt(BASE_TIME).build()),
                List.of(new TaskDependency(dependent, blocker)));

        fixture.handler().onMessage(TaskUpdatedEvent.newBuilder()
                .setTaskId(blocker.toString())
                .setTenantId(TENANT_ID.toString())
                .setStatus(com.rnd.inbox.event.TaskStatus.TASK_STATUS_DONE)
                .setUpdatedAt(timestamp(BASE_TIME.plusMinutes(5)))
                .build());

        Task dependentAfter = fixture.taskRepo().findById(dependent).orElseThrow();
        assertEquals(0, dependentAfter.getBlockingCount());
        assertTrue(dependentAfter.isReady());
    }

    @Test
    void onMessage_transitioningToCancelled_releasesDependents_sameAsDone() {
        UUID blocker = UUID.randomUUID();
        UUID dependent = UUID.randomUUID();
        Fixture fixture = newFixture(
                List.of(buildTask(blocker, TaskStatus.ACTIVE, BASE_TIME),
                        Task.builder().id(dependent).ownerId("owner").tenantId(TENANT_ID).status(TaskStatus.PENDING)
                                .isReady(false).blockingCount(1).openConstraintCount(0)
                                .createdAt(BASE_TIME).updatedAt(BASE_TIME).build()),
                List.of(new TaskDependency(dependent, blocker)));

        fixture.handler().onMessage(TaskUpdatedEvent.newBuilder()
                .setTaskId(blocker.toString())
                .setTenantId(TENANT_ID.toString())
                .setStatus(com.rnd.inbox.event.TaskStatus.TASK_STATUS_CANCELLED)
                .setUpdatedAt(timestamp(BASE_TIME.plusMinutes(5)))
                .build());

        Task dependentAfter = fixture.taskRepo().findById(dependent).orElseThrow();
        assertEquals(0, dependentAfter.getBlockingCount(),
                "an abandoned (cancelled) blocker must release its dependents, same as a completed one");
        assertTrue(dependentAfter.isReady());
    }

    @Test
    void onMessage_transitioningToRejected_releasesDependents_sameAsDone() {
        UUID blocker = UUID.randomUUID();
        UUID dependent = UUID.randomUUID();
        Fixture fixture = newFixture(
                List.of(buildTask(blocker, TaskStatus.ACTIVE, BASE_TIME),
                        Task.builder().id(dependent).ownerId("owner").tenantId(TENANT_ID).status(TaskStatus.PENDING)
                                .isReady(false).blockingCount(1).openConstraintCount(0)
                                .createdAt(BASE_TIME).updatedAt(BASE_TIME).build()),
                List.of(new TaskDependency(dependent, blocker)));

        fixture.handler().onMessage(TaskUpdatedEvent.newBuilder()
                .setTaskId(blocker.toString())
                .setTenantId(TENANT_ID.toString())
                .setStatus(com.rnd.inbox.event.TaskStatus.TASK_STATUS_REJECTED)
                .setUpdatedAt(timestamp(BASE_TIME.plusMinutes(5)))
                .build());

        Task dependentAfter = fixture.taskRepo().findById(dependent).orElseThrow();
        assertEquals(0, dependentAfter.getBlockingCount(),
                "an abandoned (rejected) blocker must release its dependents, same as a completed one");
        assertTrue(dependentAfter.isReady());
    }

    @Test
    void onMessage_withAnOlderOrEqualTimestamp_isANoOp() {
        UUID taskId = UUID.randomUUID();
        Fixture fixture = newFixture(List.of(buildTask(taskId, TaskStatus.ACTIVE, BASE_TIME)), List.of());

        fixture.handler().onMessage(TaskUpdatedEvent.newBuilder()
                .setTaskId(taskId.toString())
                .setTenantId(TENANT_ID.toString())
                .setStatus(com.rnd.inbox.event.TaskStatus.TASK_STATUS_DONE)
                .setUpdatedAt(timestamp(BASE_TIME.minusMinutes(1))) // older than stored
                .build());

        Task unchanged = fixture.taskRepo().findById(taskId).orElseThrow();
        assertEquals(TaskStatus.ACTIVE, unchanged.getStatus(), "a stale/duplicate snapshot must not overwrite a newer status");
        assertEquals(BASE_TIME, unchanged.getUpdatedAt());
    }

    @Test
    void onMessage_referencingATaskThatDoesNotExist_throwsReferencedTaskNotFoundException() {
        Fixture fixture = newFixture(List.of(), List.of());
        UUID unknownTaskId = UUID.randomUUID();

        TaskUpdatedEvent event = TaskUpdatedEvent.newBuilder()
                .setTaskId(unknownTaskId.toString())
                .setTenantId(TENANT_ID.toString())
                .setStatus(com.rnd.inbox.event.TaskStatus.TASK_STATUS_IN_PROGRESS)
                .setUpdatedAt(timestamp(BASE_TIME))
                .build();

        assertThrows(ReferencedTaskNotFoundException.class, () -> fixture.handler().onMessage(event));
    }

    @Test
    void onMessage_differentTenantThanStored_throwsNonRetryableEventException_notRetried() {
        // Confirmed with the Inbox team: tenant_id is fixed from task creation, so a task.updated
        // claiming a different one is always a bug upstream - non-retryable, straight to .errors,
        // same as a cycle/cross-tenant dependency rejection elsewhere.
        UUID taskId = UUID.randomUUID();
        Fixture fixture = newFixture(List.of(buildTask(taskId, TaskStatus.ACTIVE, BASE_TIME)), List.of());

        TaskUpdatedEvent event = TaskUpdatedEvent.newBuilder()
                .setTaskId(taskId.toString())
                .setTenantId(UUID.randomUUID().toString()) // differs from TENANT_ID stored by buildTask
                .setStatus(com.rnd.inbox.event.TaskStatus.TASK_STATUS_DONE)
                .setUpdatedAt(timestamp(BASE_TIME.plusMinutes(5)))
                .build();

        assertThrows(NonRetryableEventException.class, () -> fixture.handler().onMessage(event));
        assertEquals(TaskStatus.ACTIVE, fixture.taskRepo().findById(taskId).orElseThrow().getStatus(),
                "the status change must not apply alongside the rejected tenant change");
    }

    @Test
    void onMessage_withAnInvalidTaskId_throwsNonRetryableEventException() {
        Fixture fixture = newFixture(List.of(), List.of());
        TaskUpdatedEvent event = TaskUpdatedEvent.newBuilder()
                .setTaskId("not-a-uuid")
                .setTenantId(TENANT_ID.toString())
                .setStatus(com.rnd.inbox.event.TaskStatus.TASK_STATUS_NEW)
                .setUpdatedAt(timestamp(BASE_TIME))
                .build();

        assertThrows(NonRetryableEventException.class, () -> fixture.handler().onMessage(event));
    }
}
