package com.rnd.chain.service;

import com.rnd.chain.dto.Constraint;
import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.dto.TaskTestFixtures;
import com.rnd.chain.event.SchedulingUpdatePublisher;
import com.rnd.chain.event.TaskSchedulingUpdatedEvent;
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

class ConstraintEligibilityServiceTest {

    private InMemoryTaskRepository taskRepository;
    private InMemoryTaskDependencyRepository dependencyRepository;

    private RecordingSchedulingUpdatePublisher publisher;

    private InMemoryConstraintRepository constraintRepository;

    private ConstraintEligibilityService service;

    @BeforeEach
    void setUp() {
        taskRepository = new InMemoryTaskRepository(List.of());
        dependencyRepository = new InMemoryTaskDependencyRepository(List.of());
        publisher = new RecordingSchedulingUpdatePublisher();
        SchedulingUpdateService schedulingUpdateService = new SchedulingUpdateService(publisher);
        InMemoryGraphLock lock = new InMemoryGraphLock();
        TaskCounterUpdater counterUpdater = new TaskCounterUpdater(taskRepository, schedulingUpdateService);
        constraintRepository = new InMemoryConstraintRepository();
        service = new ConstraintEligibilityService(constraintRepository, counterUpdater);
    }

    private static Constraint newConstraint(
            UUID taskId,
            String constraintId,
            Constraint.ConstraintStatus status) {

        LocalDateTime now = LocalDateTime.now();
        return new Constraint(
                constraintId,
                taskId,
                Constraint.ConstraintType.APPROVAL_NEEDED,
                status,
                null,
                "source-ref",
                now,
                now);
    }

    @Test
    void onConstraintAdded_shouldIncreaseOpenConstraintCountAndBlockTask() {

        UUID taskId = UUID.randomUUID();

        taskRepository.save(
                createTask(
                        taskId,
                        TaskStatus.ACTIVE,
                        true,
                        0,
                        0));

        service.onConstraintAdded(newConstraint(taskId, "c-add", Constraint.ConstraintStatus.PENDING));

        Task updated =
                taskRepository.findById(taskId)
                        .orElseThrow();

        assertEquals(
                1,
                updated.getOpenConstraintCount());

        assertFalse(
                updated.isReady());

        assertEquals(
                1,
                publisher.getEvents().size());
    }

    @Test
    void onConstraintStatusChanged_whenPendingBecomesSatisfied_shouldUnblockTask() {

        UUID taskId = UUID.randomUUID();

        taskRepository.save(
                createTask(
                        taskId,
                        TaskStatus.ACTIVE,
                        false,
                        0,
                        1));

        constraintRepository.save(newConstraint(taskId, "c-sat", Constraint.ConstraintStatus.PENDING));

        service.onConstraintStatusChanged(
                taskId,
                "c-sat",
                Constraint.ConstraintStatus.SATISFIED,
                null);

        Task updated =
                taskRepository.findById(taskId)
                        .orElseThrow();

        assertEquals(
                0,
                updated.getOpenConstraintCount());

        assertTrue(
                updated.isReady());

        assertEquals(
                1,
                publisher.getEvents().size());
    }

    @Test
    void onConstraintStatusChanged_whenOpenStateDoesNotChange_shouldDoNothing() {

        UUID taskId = UUID.randomUUID();

        taskRepository.save(
                createTask(
                        taskId,
                        TaskStatus.ACTIVE,
                        false,
                        0,
                        1));

        constraintRepository.save(newConstraint(taskId, "c-same", Constraint.ConstraintStatus.PENDING));

        service.onConstraintStatusChanged(
                taskId,
                "c-same",
                Constraint.ConstraintStatus.PENDING,
                null);

        Task updated =
                taskRepository.findById(taskId)
                        .orElseThrow();

        assertEquals(
                1,
                updated.getOpenConstraintCount());

        assertFalse(
                updated.isReady());

        assertTrue(
                publisher.getEvents().isEmpty());
    }

    @Test
    void onConstraintAdded_incrementsOpenConstraintCount_andStaysNotReady() {
        UUID taskId = UUID.randomUUID();
        String constraintId = UUID.randomUUID().toString();
        Fixture fixture = newFixture(List.of(buildTask(taskId, 0, 0, true)), List.of());

        fixture.service().onConstraintAdded(newConstraint(taskId, "c-add2", Constraint.ConstraintStatus.PENDING));

        Task task = fixture.taskRepo().findById(taskId).orElseThrow();
        assertEquals(1, task.getOpenConstraintCount());
        assertFalse(task.isReady());
    }

    @Test
    void onConstraintStatusChanged_closingLastConstraint_becomesReady() {
        UUID taskId = UUID.randomUUID();
        String constraintId = UUID.randomUUID().toString();
        Fixture fixture = newFixture(List.of(buildTask(taskId, 0, 0, false)), List.of());

        fixture.constraintRepo().save(newConstraint(taskId, "c-close2", Constraint.ConstraintStatus.PENDING));

        fixture.service().onConstraintStatusChanged(
                taskId, "c-close2", Constraint.ConstraintStatus.SATISFIED, null);

        Task task = fixture.taskRepo().findById(taskId).orElseThrow();
        assertEquals(0, task.getOpenConstraintCount());
        assertTrue(task.isReady());
    }

    @Test
    void onConstraintStatusChanged_reopeningConstraint_becomesNotReadyAgain() {
        UUID taskId = UUID.randomUUID();
        String constraintId = UUID.randomUUID().toString();
        Fixture fixture = newFixture(List.of(buildTask(taskId, 0, 0, true)), List.of());

        fixture.constraintRepo().save(newConstraint(taskId, "c-reopen", Constraint.ConstraintStatus.SATISFIED));

        fixture.service().onConstraintStatusChanged(
                taskId, "c-reopen", Constraint.ConstraintStatus.PENDING, null);

        Task task = fixture.taskRepo().findById(taskId).orElseThrow();
        assertEquals(1, task.getOpenConstraintCount());
        assertFalse(task.isReady());
    }

    @Test
    void onConstraintStatusChanged_nonOpenTransition_taskDoesNotExist_throws() {
        // Regression test (review finding): the fast path for a non-open-changing transition
        // (e.g. SATISFIED -> WAIVED) previously returned before validating taskId at all,
        // contradicting this method's own @throws contract and every other public method's
        // convention of always validating task existence.
        UUID taskId = UUID.randomUUID();
        Fixture fixture = newFixture(List.of(), List.of());

        fixture.constraintRepo().save(newConstraint(taskId, "c-waive", Constraint.ConstraintStatus.SATISFIED));

        assertDoesNotThrow(() -> fixture.service().onConstraintStatusChanged(
                taskId, "c-waive", Constraint.ConstraintStatus.WAIVED, null));
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
                            ConstraintEligibilityService service) {
    }

    private static Fixture newFixture(List<Task> tasks, List<TaskDependency> edges) {
        InMemoryTaskRepository taskRepo = new InMemoryTaskRepository(tasks);
        InMemoryTaskDependencyRepository dependencyRepo = new InMemoryTaskDependencyRepository(edges);
        InMemoryConstraintRepository constraintRepo = new InMemoryConstraintRepository();
        SchedulingUpdateService scheduling = new SchedulingUpdateService(event -> {});
        TaskCounterUpdater counterUpdater = new TaskCounterUpdater(taskRepo, scheduling);
        return new Fixture(taskRepo, dependencyRepo, constraintRepo, new ConstraintEligibilityService(constraintRepo, counterUpdater));
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
