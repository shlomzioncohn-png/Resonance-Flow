package com.rnd.chain.events;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.dto.TaskStatus;
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
                .status(TaskStatus.PENDING)
                .isReady(true)
                .blockingCount(0)
                .openConstraintCount(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private record Fixture(InMemoryTaskRepository taskRepo, DependencyProposedEventHandler handler) {
    }

    private static Fixture newFixture(List<Task> tasks) {
        InMemoryTaskRepository taskRepo = new InMemoryTaskRepository(tasks);
        InMemoryTaskDependencyRepository dependencyRepo = new InMemoryTaskDependencyRepository(List.of());
        TaskEligibilityService eligibilityService = new TaskEligibilityService(taskRepo, dependencyRepo);
        ChainEventProcessingTemplate template = new ChainEventProcessingTemplate(new InMemoryProcessedEventStore());
        return new Fixture(taskRepo, new DependencyProposedEventHandler(eligibilityService, template));
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
        TaskEligibilityService eligibilityService = new TaskEligibilityService(taskRepo, dependencyRepo);
        ChainEventProcessingTemplate template = new ChainEventProcessingTemplate(new InMemoryProcessedEventStore());
        DependencyProposedEventHandler handler = new DependencyProposedEventHandler(eligibilityService, template);

        DependencyProposedEvent event = DependencyProposedEvent.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setTaskId(taskA.toString())
                .addDependencies(com.rnd.mind.event.Dependency.newBuilder().setDependsOnTaskId(taskB.toString()).build())
                .build();

        assertThrows(NonRetryableEventException.class, () -> handler.onMessage(event));
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
