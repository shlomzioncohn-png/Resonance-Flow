package com.rnd.chain.events;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.dto.TaskTestFixtures;
import com.rnd.chain.repository.InMemoryConstraintRepository;
import com.rnd.chain.repository.InMemoryDependencyHistoryRepository;
import com.rnd.chain.service.SchedulingUpdateService;
import static org.mockito.Mockito.mock;
import com.rnd.chain.repository.InMemoryTaskDependencyRepository;
import com.rnd.chain.repository.InMemoryTaskRepository;
import com.rnd.chain.service.TaskEligibilityService;
import com.rnd.mind.event.DependencyProposedEvent;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DependencyProposedEventHandlerTest {

    private static Task buildTask(UUID id) {
        return Task.builder()
                .id(id)
                .ownerId("owner")
                .tenantId(TaskTestFixtures.DEFAULT_TENANT_ID)
                .status(TaskStatus.PENDING)
                .isReady(true)
                .blockingCount(0)
                .openConstraintCount(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private record Fixture(InMemoryTaskRepository taskRepo, InMemoryDependencyHistoryRepository historyRepo,
                            DependencyProposedEventHandler handler) {
    }

    private static Fixture newFixture(List<Task> tasks) {
        InMemoryTaskRepository taskRepo = new InMemoryTaskRepository(tasks);
        InMemoryTaskDependencyRepository dependencyRepo = new InMemoryTaskDependencyRepository(List.of());
        InMemoryDependencyHistoryRepository historyRepo = new InMemoryDependencyHistoryRepository();
        TaskEligibilityService eligibilityService = new TaskEligibilityService(taskRepo, dependencyRepo,
                mock(SchedulingUpdateService.class), new InMemoryConstraintRepository());
        ChainEventProcessingTemplate template = new ChainEventProcessingTemplate(new InMemoryProcessedEventStore());
        return new Fixture(taskRepo, historyRepo,
                new DependencyProposedEventHandler(eligibilityService, template, historyRepo));
    }

    @Test
    void onMessage_addsEveryDependencyInTheArray() {
        UUID taskId = UUID.randomUUID();
        UUID dep1 = UUID.randomUUID();
        UUID dep2 = UUID.randomUUID();
        Fixture fixture = newFixture(List.of(buildTask(taskId), buildTask(dep1), buildTask(dep2)));

        fixture.handler().onMessage(DependencyProposedEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setTaskId(taskId.toString())
                .addDependencies(com.rnd.mind.event.Dependency.newBuilder().setDependsOnTaskId(dep1.toString()).build())
                .addDependencies(com.rnd.mind.event.Dependency.newBuilder().setDependsOnTaskId(dep2.toString()).build())
                .build());

        Task after = fixture.taskRepo().findById(taskId).orElseThrow();
        assertEquals(2, after.getBlockingCount());
        assertFalse(after.isReady());
    }

    @Test
    void onMessage_redeliveredWithTheSameEventId_isNotAppliedTwice() {
        UUID taskId = UUID.randomUUID();
        UUID dep = UUID.randomUUID();
        Fixture fixture = newFixture(List.of(buildTask(taskId), buildTask(dep)));
        DependencyProposedEvent event = DependencyProposedEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setTaskId(taskId.toString())
                .addDependencies(com.rnd.mind.event.Dependency.newBuilder().setDependsOnTaskId(dep.toString()).build())
                .build();

        fixture.handler().onMessage(event);
        fixture.handler().onMessage(event); // exact same event_id, redelivered

        Task after = fixture.taskRepo().findById(taskId).orElseThrow();
        assertEquals(1, after.getBlockingCount(), "redelivery of the same event_id must not double-count");
    }

    @Test
    void onMessage_crossTenantDependency_throwsNonRetryableEventException_andRecordsRejectedHistory() {
        UUID taskId = UUID.randomUUID();
        UUID otherTenantDep = UUID.randomUUID();
        Task crossTenantTask = Task.builder()
                .id(otherTenantDep).ownerId("owner").tenantId(TaskTestFixtures.OTHER_TENANT_ID)
                .status(TaskStatus.PENDING).isReady(true).blockingCount(0).openConstraintCount(0)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        Fixture fixture = newFixture(List.of(buildTask(taskId), crossTenantTask));
        UUID eventId = UUID.randomUUID();

        DependencyProposedEvent event = DependencyProposedEvent.newBuilder()
                .setEventId(eventId.toString())
                .setTaskId(taskId.toString())
                .addDependencies(com.rnd.mind.event.Dependency.newBuilder().setDependsOnTaskId(otherTenantDep.toString()).build())
                .build();

        assertThrows(NonRetryableEventException.class, () -> fixture.handler().onMessage(event));

        assertTrue(fixture.taskRepo().findById(taskId).map(t -> t.getBlockingCount() == 0).orElse(false),
                "a rejected edge must never be applied");
        List<com.rnd.chain.dto.DependencyHistoryEntry> entries =
                fixture.historyRepo().findByTaskId(taskId, 0, 10).content();
        assertEquals(1, entries.size());
        assertEquals(com.rnd.chain.dto.DependencyChangeAction.REJECTED, entries.get(0).action());
        assertEquals(com.rnd.chain.dto.DependencyChangeSource.MIND, entries.get(0).source());
    }

    @Test
    void onMessage_referencingATaskThatDoesNotExistYet_throwsReferencedTaskNotFoundException() {
        UUID taskId = UUID.randomUUID();
        UUID unknownDep = UUID.randomUUID();
        Fixture fixture = newFixture(List.of(buildTask(taskId)));

        DependencyProposedEvent event = DependencyProposedEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setTaskId(taskId.toString())
                .addDependencies(com.rnd.mind.event.Dependency.newBuilder().setDependsOnTaskId(unknownDep.toString()).build())
                .build();

        assertThrows(ReferencedTaskNotFoundException.class, () -> fixture.handler().onMessage(event));
    }

    @Test
    void onMessage_thatWouldCreateACycle_throwsNonRetryableEventException() {
        UUID taskA = UUID.randomUUID();
        UUID taskB = UUID.randomUUID();
        InMemoryTaskRepository taskRepo = new InMemoryTaskRepository(List.of(buildTask(taskA), buildTask(taskB)));
        // B already depends on A - proposing A depends on B would close a cycle.
        InMemoryTaskDependencyRepository dependencyRepo =
                new InMemoryTaskDependencyRepository(List.of(new TaskDependency(taskB, taskA)));
        TaskEligibilityService eligibilityService = new TaskEligibilityService(taskRepo, dependencyRepo,
                mock(SchedulingUpdateService.class), new InMemoryConstraintRepository());
        ChainEventProcessingTemplate template = new ChainEventProcessingTemplate(new InMemoryProcessedEventStore());
        DependencyProposedEventHandler handler = new DependencyProposedEventHandler(eligibilityService, template,
                new InMemoryDependencyHistoryRepository());

        DependencyProposedEvent event = DependencyProposedEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setTaskId(taskA.toString())
                .addDependencies(com.rnd.mind.event.Dependency.newBuilder().setDependsOnTaskId(taskB.toString()).build())
                .build();

        assertThrows(NonRetryableEventException.class, () -> handler.onMessage(event));
    }

    @Test
    void onMessage_withOneCycleAmongSeveralDependencies_appliesNoneOfThem() {
        // Regression test for the PR #467 review finding: A already depends on B (A -> B).
        // Proposing B depends on [C (valid), A (would close a cycle)] - the valid entry (C) comes
        // first, so the old per-entry-applied-immediately code would have already added it before
        // ever reaching the cycle at the second entry.
        UUID taskA = UUID.randomUUID();
        UUID taskB = UUID.randomUUID();
        UUID taskC = UUID.randomUUID();
        InMemoryTaskRepository taskRepo = new InMemoryTaskRepository(List.of(buildTask(taskB), buildTask(taskC)));
        InMemoryTaskDependencyRepository dependencyRepo =
                new InMemoryTaskDependencyRepository(List.of(new TaskDependency(taskA, taskB)));
        TaskEligibilityService eligibilityService = new TaskEligibilityService(taskRepo, dependencyRepo,
                mock(SchedulingUpdateService.class), new InMemoryConstraintRepository());
        ChainEventProcessingTemplate template = new ChainEventProcessingTemplate(new InMemoryProcessedEventStore());
        DependencyProposedEventHandler handler = new DependencyProposedEventHandler(eligibilityService, template,
                new InMemoryDependencyHistoryRepository());

        DependencyProposedEvent event = DependencyProposedEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setTaskId(taskB.toString())
                .addDependencies(com.rnd.mind.event.Dependency.newBuilder().setDependsOnTaskId(taskC.toString()).build())
                .addDependencies(com.rnd.mind.event.Dependency.newBuilder().setDependsOnTaskId(taskA.toString()).build())
                .build();

        assertThrows(NonRetryableEventException.class, () -> handler.onMessage(event));

        assertTrue(dependencyRepo.findByTaskId(taskB).isEmpty(),
                "the valid entry (C) earlier in the batch must not be applied when a later entry fails");
    }

    @Test
    void onMessage_withAnInvalidEventId_throwsNonRetryableEventException() {
        Fixture fixture = newFixture(List.of());
        DependencyProposedEvent event = DependencyProposedEvent.newBuilder()
                .setEventId("not-a-uuid")
                .setTaskId(UUID.randomUUID().toString())
                .build();

        assertThrows(NonRetryableEventException.class, () -> fixture.handler().onMessage(event));
    }

    @Test
    void onMessage_withAnInvalidDependencyTaskId_throwsNonRetryableEventException() {
        UUID taskId = UUID.randomUUID();
        Fixture fixture = newFixture(List.of(buildTask(taskId)));
        DependencyProposedEvent event = DependencyProposedEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setTaskId(taskId.toString())
                .addDependencies(com.rnd.mind.event.Dependency.newBuilder().setDependsOnTaskId("not-a-uuid").build())
                .build();

        assertThrows(NonRetryableEventException.class, () -> fixture.handler().onMessage(event));
    }
}
