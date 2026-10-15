package com.rnd.chain.events;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.dto.TaskTestFixtures;
import com.rnd.chain.repository.InMemoryDependencyHistoryRepository;
import com.rnd.chain.repository.InMemoryGraphLock;
import com.rnd.chain.repository.InMemoryTaskDependencyRepository;
import com.rnd.chain.repository.InMemoryTaskRepository;
import com.rnd.chain.repository.InMemoryTransactionRunner;
import com.rnd.chain.events.SchedulingPublishListener;
import com.rnd.chain.service.DependencyEdgeService;
import com.rnd.chain.service.PriorityScoreService;
import com.rnd.chain.service.SchedulingUpdateService;
import com.rnd.chain.service.TaskCounterUpdater;
import com.rnd.chain.service.TaskDependencyService;
import com.rnd.mind.event.DependencyProposedEvent;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class DependencyProposedEventHandlerTest {

    private static Task buildTask(UUID id) {
        return Task.builder()
                .id(id)
                .tenantId(TaskTestFixtures.DEFAULT_TENANT_ID)
                .status(TaskStatus.PENDING)
                .isReady(true)
                .blockingCount(0)
                .openConstraintCount(0)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private static DependencyProposedEvent.Builder eventBuilder() {
        return DependencyProposedEvent.newBuilder()
                .setTenantId(TaskTestFixtures.DEFAULT_TENANT_ID.toString());
    }

    private static com.rnd.mind.event.Dependency dependencyOn(UUID id) {
        return com.rnd.mind.event.Dependency.newBuilder().setDependsOnTaskId(id.toString()).build();
    }

    private record Fixture(
            InMemoryTaskRepository taskRepo,
            InMemoryTaskDependencyRepository dependencyRepo,
            InMemoryDependencyHistoryRepository historyRepo,
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
                        new InMemoryGraphLock(),
                        new TaskCounterUpdater(taskRepo, dependencyRepo, new PriorityScoreService(),
                                new SchedulingPublishListener(taskRepo, schedulingUpdateService)));

        InMemoryDependencyHistoryRepository historyRepo =
                new InMemoryDependencyHistoryRepository();

        TaskDependencyService dependencyService =
                new TaskDependencyService(
                        taskRepo,
                        dependencyRepo,
                        historyRepo,
                        new InMemoryTransactionRunner(),
                        Clock.systemUTC(),
                        edgeService);

        ChainEventProcessingTemplate template =
                new ChainEventProcessingTemplate(
                        new InMemoryProcessedEventStore());

        DependencyProposedEventHandler handler =
                new DependencyProposedEventHandler(
                        dependencyService,
                        template);

        return new Fixture(
                taskRepo,
                dependencyRepo,
                historyRepo,
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
                eventBuilder()
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
                eventBuilder()
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
    void onMessage_crossTenantDependency_throwsNonRetryableEventException_andRecordsRejectedHistory() {
        UUID taskId = UUID.randomUUID();
        UUID otherTenantDep = UUID.randomUUID();
        Task crossTenantTask = Task.builder()
                .id(otherTenantDep).tenantId(TaskTestFixtures.OTHER_TENANT_ID)
                .status(TaskStatus.PENDING).isReady(true).blockingCount(0).openConstraintCount(0)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        Fixture fixture = newFixture(List.of(buildTask(taskId), crossTenantTask));
        UUID eventId = UUID.randomUUID();

        DependencyProposedEvent event = eventBuilder()
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
    void onMessage_missingTarget_createsPendingPlaceholderAndAddsTheEdge() {
        UUID taskId = UUID.randomUUID();
        UUID missingDep = UUID.randomUUID();
        Fixture fixture = newFixture(List.of(buildTask(taskId)));

        fixture.handler().onMessage(eventBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setTaskId(taskId.toString())
                .addDependencies(dependencyOn(missingDep))
                .build());

        Task placeholder = fixture.taskRepo().findById(missingDep).orElseThrow();
        assertEquals(TaskStatus.PENDING, placeholder.getStatus());
        assertEquals(TaskTestFixtures.DEFAULT_TENANT_ID, placeholder.getTenantId());
        assertEquals(0, placeholder.getBlockingCount());
        assertEquals(0, placeholder.getOpenConstraintCount());
        assertEquals(LocalDateTime.ofEpochSecond(0, 0, ZoneOffset.UTC), placeholder.getUpdatedAt());
        assertEquals(1, fixture.dependencyRepo().findByTaskId(taskId).size());
        assertEquals(1, fixture.taskRepo().findById(taskId).orElseThrow().getBlockingCount());
    }

    @Test
    void onMessage_missingSource_createsPendingPlaceholderAndAddsTheEdge() {
        UUID missingSource = UUID.randomUUID();
        UUID dependencyId = UUID.randomUUID();
        Fixture fixture = newFixture(List.of(buildTask(dependencyId)));

        fixture.handler().onMessage(eventBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setTaskId(missingSource.toString())
                .addDependencies(dependencyOn(dependencyId))
                .build());

        Task source = fixture.taskRepo().findById(missingSource).orElseThrow();
        assertEquals(TaskStatus.PENDING, source.getStatus());
        assertEquals(TaskTestFixtures.DEFAULT_TENANT_ID, source.getTenantId());
        assertEquals(1, source.getBlockingCount());
        assertEquals(1, fixture.dependencyRepo().findByTaskId(missingSource).size());
    }

    @Test
    void onMessage_bothTasksMissing_createsBothAndAddsTheEdge() {
        UUID missingSource = UUID.randomUUID();
        UUID missingDep = UUID.randomUUID();
        Fixture fixture = newFixture(List.of());

        fixture.handler().onMessage(eventBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setTaskId(missingSource.toString())
                .addDependencies(dependencyOn(missingDep))
                .build());

        assertEquals(TaskStatus.PENDING, fixture.taskRepo().findById(missingSource).orElseThrow().getStatus());
        assertEquals(TaskStatus.PENDING, fixture.taskRepo().findById(missingDep).orElseThrow().getStatus());
        assertEquals(1, fixture.dependencyRepo().findByTaskId(missingSource).size());
    }

    @Test
    void onMessage_existingTasks_areNotOverwrittenByPlaceholders() {
        UUID taskId = UUID.randomUUID();
        UUID depId = UUID.randomUUID();
        Task existingDep = Task.builder()
                .id(depId).tenantId(TaskTestFixtures.DEFAULT_TENANT_ID)
                .status(TaskStatus.ACTIVE).isReady(true).blockingCount(0).openConstraintCount(0)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        Fixture fixture = newFixture(List.of(buildTask(taskId), existingDep));

        fixture.handler().onMessage(eventBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setTaskId(taskId.toString())
                .addDependencies(dependencyOn(depId))
                .build());

        Task after = fixture.taskRepo().findById(depId).orElseThrow();
        assertEquals(TaskStatus.ACTIVE, after.getStatus());
        assertEquals(existingDep.getUpdatedAt(), after.getUpdatedAt());
    }

    @Test
    void onMessage_missingTargetWithEventTenantDifferentFromExistingSource_isRejectedAndCreatesNothing() {
        UUID taskId = UUID.randomUUID();
        UUID missingDep = UUID.randomUUID();
        Fixture fixture = newFixture(List.of(buildTask(taskId)));

        DependencyProposedEvent event = DependencyProposedEvent.newBuilder()
                .setTenantId(TaskTestFixtures.OTHER_TENANT_ID.toString())
                .setEventId(UUID.randomUUID().toString())
                .setTaskId(taskId.toString())
                .addDependencies(dependencyOn(missingDep))
                .build();

        assertThrows(NonRetryableEventException.class, () -> fixture.handler().onMessage(event));

        assertTrue(fixture.dependencyRepo().findByTaskId(taskId).isEmpty());
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
                eventBuilder()
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
                eventBuilder()
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
                eventBuilder()
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
                eventBuilder()
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