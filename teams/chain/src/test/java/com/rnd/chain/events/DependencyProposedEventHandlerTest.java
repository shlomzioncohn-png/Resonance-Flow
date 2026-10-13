package com.rnd.chain.events;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.repository.InMemoryDependencyHistoryRepository;
import com.rnd.chain.repository.InMemoryTaskDependencyRepository;
import com.rnd.chain.repository.InMemoryTaskRepository;
import com.rnd.chain.repository.InMemoryTransactionRunner;
import com.rnd.chain.repository.InMemoryUnresolvedDependencyRepository;
import com.rnd.chain.events.SchedulingPublishListener;
import com.rnd.chain.service.DependencyEdgeService;
import com.rnd.chain.service.DependencyGraphLock;
import com.rnd.chain.service.PriorityScoreService;
import com.rnd.chain.service.SchedulingUpdateService;
import com.rnd.chain.service.TaskCounterUpdater;
import com.rnd.chain.service.TaskDependencyService;
import com.rnd.chain.service.UnresolvedDependencyService;
import com.rnd.mind.event.DependencyProposedEvent;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

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

    private record Fixture(
            InMemoryTaskRepository taskRepo,
            InMemoryTaskDependencyRepository dependencyRepo,
            InMemoryUnresolvedDependencyRepository unresolvedRepo,
            DependencyProposedEventHandler handler) {
    }

    private static Fixture newFixture(List<Task> tasks) {
        return newFixture(tasks, List.of());
    }

    private static Fixture newFixture(
            List<Task> tasks,
            List<TaskDependency> existingDependencies) {

        InMemoryTaskRepository taskRepo =
                new InMemoryTaskRepository(tasks);

        InMemoryTaskDependencyRepository dependencyRepo =
                new InMemoryTaskDependencyRepository(
                        existingDependencies);

        SchedulingUpdateService schedulingUpdateService = mock(SchedulingUpdateService.class);
        DependencyEdgeService edgeService =
                new DependencyEdgeService(
                        dependencyRepo,
                        new DependencyGraphLock(),
                        new TaskCounterUpdater(taskRepo, dependencyRepo, new PriorityScoreService(),
                                new SchedulingPublishListener(taskRepo, schedulingUpdateService)));

        TaskDependencyService dependencyService =
                new TaskDependencyService(
                        taskRepo,
                        dependencyRepo,
                        new InMemoryDependencyHistoryRepository(),
                        new InMemoryTransactionRunner(),
                        Clock.systemUTC(),
                        edgeService);

        InMemoryUnresolvedDependencyRepository unresolvedRepo =
                new InMemoryUnresolvedDependencyRepository();

        UnresolvedDependencyService unresolvedService =
                new UnresolvedDependencyService(
                        unresolvedRepo,
                        dependencyService,
                        Clock.systemUTC());

        ChainEventProcessingTemplate template =
                new ChainEventProcessingTemplate(
                        new InMemoryProcessedEventStore());

        DependencyProposedEventHandler handler =
                new DependencyProposedEventHandler(
                        dependencyService,
                        taskRepo,
                        unresolvedService,
                        template);

        return new Fixture(
                taskRepo,
                dependencyRepo,
                unresolvedRepo,
                handler);
    }

    @Test
    void onMessage_addsEveryDependencyInTheArray() {

        UUID taskId = UUID.randomUUID();
        UUID dep1 = UUID.randomUUID();
        UUID dep2 = UUID.randomUUID();

        Fixture fixture = newFixture(
                List.of(
                        buildTask(taskId),
                        buildTask(dep1),
                        buildTask(dep2)));

        fixture.handler().onMessage(
                DependencyProposedEvent.newBuilder()
                        .setEventId(UUID.randomUUID().toString())
                        .setTaskId(taskId.toString())
                        .addDependencies(
                                com.rnd.mind.event.Dependency
                                        .newBuilder()
                                        .setDependsOnTaskId(
                                                dep1.toString())
                                        .build())
                        .addDependencies(
                                com.rnd.mind.event.Dependency
                                        .newBuilder()
                                        .setDependsOnTaskId(
                                                dep2.toString())
                                        .build())
                        .build());

        Task after =
                fixture.taskRepo()
                        .findById(taskId)
                        .orElseThrow();

        assertEquals(2, after.getBlockingCount());
        assertFalse(after.isReady());

        assertEquals(
                2,
                fixture.dependencyRepo()
                        .findByTaskId(taskId)
                        .size());
    }

    @Test
    void onMessage_redeliveredWithTheSameEventId_isNotAppliedTwice() {

        UUID taskId = UUID.randomUUID();
        UUID dep = UUID.randomUUID();

        Fixture fixture =
                newFixture(
                        List.of(
                                buildTask(taskId),
                                buildTask(dep)));

        DependencyProposedEvent event =
                DependencyProposedEvent.newBuilder()
                        .setEventId(UUID.randomUUID().toString())
                        .setTaskId(taskId.toString())
                        .addDependencies(
                                com.rnd.mind.event.Dependency
                                        .newBuilder()
                                        .setDependsOnTaskId(
                                                dep.toString())
                                        .build())
                        .build();

        fixture.handler().onMessage(event);
        fixture.handler().onMessage(event);

        Task after =
                fixture.taskRepo()
                        .findById(taskId)
                        .orElseThrow();

        assertEquals(
                1,
                after.getBlockingCount());

        assertEquals(
                1,
                fixture.dependencyRepo()
                        .findByTaskId(taskId)
                        .size());
    }

    @Test
    void onMessage_missingTarget_savesUnresolvedDependency() {

        UUID taskId = UUID.randomUUID();
        UUID unknownDep = UUID.randomUUID();

        Fixture fixture =
                newFixture(
                        List.of(buildTask(taskId)));

        UUID eventId = UUID.randomUUID();

        DependencyProposedEvent event =
                DependencyProposedEvent.newBuilder()
                        .setEventId(eventId.toString())
                        .setTaskId(taskId.toString())
                        .addDependencies(
                                com.rnd.mind.event.Dependency
                                        .newBuilder()
                                        .setDependsOnTaskId(
                                                unknownDep.toString())
                                        .build())
                        .build();

        assertDoesNotThrow(
                () -> fixture.handler().onMessage(event));

        var unresolved =
                fixture.unresolvedRepo()
                        .findByTaskIdAndDependsOnTaskId(
                                taskId,
                                unknownDep);

        assertTrue(unresolved.isPresent());

        assertEquals(
                eventId + ":" + taskId + ":" + unknownDep,
                unresolved.orElseThrow().sourceRef());

        assertTrue(
                fixture.dependencyRepo()
                        .findByTaskId(taskId)
                        .isEmpty());
    }

    @Test
    void onMessage_missingSource_throwsReferencedTaskNotFoundException() {

        UUID missingSource = UUID.randomUUID();
        UUID dependencyId = UUID.randomUUID();

        Fixture fixture =
                newFixture(
                        List.of(buildTask(dependencyId)));

        DependencyProposedEvent event =
                DependencyProposedEvent.newBuilder()
                        .setEventId(UUID.randomUUID().toString())
                        .setTaskId(missingSource.toString())
                        .addDependencies(
                                com.rnd.mind.event.Dependency
                                        .newBuilder()
                                        .setDependsOnTaskId(
                                                dependencyId.toString())
                                        .build())
                        .build();

        assertThrows(
                ReferencedTaskNotFoundException.class,
                () -> fixture.handler().onMessage(event));
    }

    @Test
    void onMessage_thatWouldCreateACycle_throwsNonRetryableEventException() {

        UUID taskA = UUID.randomUUID();
        UUID taskB = UUID.randomUUID();

        Fixture fixture =
                newFixture(
                        List.of(
                                buildTask(taskA),
                                buildTask(taskB)),
                        List.of(
                                new TaskDependency(
                                        taskB,
                                        taskA)));

        /*
         * B already depends on A.
         * Proposing A -> B closes the cycle.
         */
        DependencyProposedEvent event =
                DependencyProposedEvent.newBuilder()
                        .setEventId(UUID.randomUUID().toString())
                        .setTaskId(taskA.toString())
                        .addDependencies(
                                com.rnd.mind.event.Dependency
                                        .newBuilder()
                                        .setDependsOnTaskId(
                                                taskB.toString())
                                        .build())
                        .build();

        assertThrows(
                NonRetryableEventException.class,
                () -> fixture.handler().onMessage(event));
    }

    @Test
    void onMessage_withValidDependencyBeforeCycle_keepsPartialSuccess() {

        UUID taskA = UUID.randomUUID();
        UUID taskB = UUID.randomUUID();
        UUID taskC = UUID.randomUUID();

        /*
         * A -> B already exists.
         *
         * Event proposes:
         * B -> C   valid
         * B -> A   cycle
         *
         * The first dependency is allowed to succeed before the
         * second one is rejected.
         */
        Fixture fixture =
                newFixture(
                        List.of(
                                buildTask(taskA),
                                buildTask(taskB),
                                buildTask(taskC)),
                        List.of(
                                new TaskDependency(
                                        taskA,
                                        taskB)));

        DependencyProposedEvent event =
                DependencyProposedEvent.newBuilder()
                        .setEventId(UUID.randomUUID().toString())
                        .setTaskId(taskB.toString())
                        .addDependencies(
                                com.rnd.mind.event.Dependency
                                        .newBuilder()
                                        .setDependsOnTaskId(
                                                taskC.toString())
                                        .build())
                        .addDependencies(
                                com.rnd.mind.event.Dependency
                                        .newBuilder()
                                        .setDependsOnTaskId(
                                                taskA.toString())
                                        .build())
                        .build();

        assertThrows(
                NonRetryableEventException.class,
                () -> fixture.handler().onMessage(event));

        List<TaskDependency> dependencies =
                fixture.dependencyRepo()
                        .findByTaskId(taskB);

        assertTrue(
                dependencies.stream()
                        .anyMatch(dep ->
                                dep.dependsOnTaskId()
                                        .equals(taskC)));
    }

    @Test
    void onMessage_withAnInvalidEventId_throwsNonRetryableEventException() {

        Fixture fixture =
                newFixture(List.of());

        DependencyProposedEvent event =
                DependencyProposedEvent.newBuilder()
                        .setEventId("not-a-uuid")
                        .setTaskId(
                                UUID.randomUUID().toString())
                        .build();

        assertThrows(
                NonRetryableEventException.class,
                () -> fixture.handler().onMessage(event));
    }

    @Test
    void onMessage_withAnInvalidDependencyTaskId_throwsNonRetryableEventException() {

        UUID taskId = UUID.randomUUID();

        Fixture fixture =
                newFixture(
                        List.of(buildTask(taskId)));

        DependencyProposedEvent event =
                DependencyProposedEvent.newBuilder()
                        .setEventId(UUID.randomUUID().toString())
                        .setTaskId(taskId.toString())
                        .addDependencies(
                                com.rnd.mind.event.Dependency
                                        .newBuilder()
                                        .setDependsOnTaskId(
                                                "not-a-uuid")
                                        .build())
                        .build();

        assertThrows(
                NonRetryableEventException.class,
                () -> fixture.handler().onMessage(event));
    }
}