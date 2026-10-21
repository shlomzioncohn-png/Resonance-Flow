package com.rnd.chain.service;

import com.rnd.chain.testsupport.PgRepos;
import com.rnd.chain.testsupport.TaskStateHistoryFixtures;

import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.rnd.chain.dto.Constraint;
import com.rnd.chain.dto.ConstraintHistoryAction;
import com.rnd.chain.dto.ConstraintHistoryEntry;
import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.dto.TaskTestFixtures;
import com.rnd.chain.events.SchedulingPublishListener;
import com.rnd.chain.events.SchedulingUpdatePublisher;
import com.rnd.chain.events.TaskSchedulingUpdatedEvent;
import com.rnd.chain.repository.ConstraintHistoryRepository;
import com.rnd.chain.repository.ConstraintRepository;
import com.rnd.chain.repository.TaskDependencyRepository;
import com.rnd.chain.repository.TaskReadinessRepository;
import com.rnd.chain.repository.TaskRepository;
import com.rnd.chain.testsupport.TestTransactions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@Tag("postgres")
@SpringBootTest
@Transactional
class ConstraintEligibilityServiceTest {

    private TaskRepository taskRepository;
    private TaskDependencyRepository dependencyRepository;

    private RecordingSchedulingUpdatePublisher publisher;

    private ConstraintRepository constraintRepository;

    private ConstraintHistoryRepository historyRepository;

    private ConstraintEligibilityService service;

    @Autowired
    private TaskReadinessRepository taskReadinessRepository;

    @BeforeEach
    void setUp() {
        taskRepository = PgRepos.tasks(List.of());
        dependencyRepository = PgRepos.dependencies(List.of());
        publisher = new RecordingSchedulingUpdatePublisher();

        SchedulingUpdateService schedulingUpdateService =
                new SchedulingUpdateService(publisher);

        TaskCounterUpdater counterUpdater =
                new TaskCounterUpdater(
                        taskRepository,
                        new PriorityScoreService(
                                taskRepository,
                                dependencyRepository,
                                taskReadinessRepository,
                                schedulingUpdateService,
                                new SchedulingPublishListener(
                                taskRepository,
                                schedulingUpdateService)),
                        taskReadinessRepository);

        constraintRepository = PgRepos.constraints();
        historyRepository = mock(ConstraintHistoryRepository.class);

        service =
                new ConstraintEligibilityService(
                        taskRepository,
                        constraintRepository,
                        historyRepository,
                        counterUpdater,
                        taskReadinessRepository,
                        TaskStateHistoryFixtures.newRecorder());
    }

    private ConstraintHistoryEntry singleRecordedEntry() {
        ArgumentCaptor<ConstraintHistoryEntry> captor = ArgumentCaptor.forClass(ConstraintHistoryEntry.class);
        verify(historyRepository).record(captor.capture());
        return captor.getValue();
    }

    @Test
    void onConstraintAdded_recordsExactlyOneCreatedEntry() {
        UUID taskId = UUID.randomUUID();
        taskRepository.save(createTask(taskId, TaskStatus.ACTIVE, true, 0, 0));

        service.onConstraintAdded(newConstraint(taskId, "c-hist-add", Constraint.ConstraintStatus.PENDING));

        ConstraintHistoryEntry entry = singleRecordedEntry();
        assertEquals(ConstraintHistoryAction.CREATED, entry.action());
        assertEquals("c-hist-add", entry.constraintId());
        assertEquals(taskId, entry.taskId());
        assertNull(entry.previousStatus());
        assertEquals(Constraint.ConstraintStatus.PENDING, entry.newStatus());
        assertEquals("source-ref", entry.sourceRef());
    }

    @Test
    void onConstraintAdded_duplicateAdd_recordsNoSecondEntry() {
        UUID taskId = UUID.randomUUID();
        taskRepository.save(createTask(taskId, TaskStatus.ACTIVE, true, 0, 0));
        Constraint constraint = newConstraint(taskId, "c-hist-dup", Constraint.ConstraintStatus.PENDING);

        service.onConstraintAdded(constraint);
        service.onConstraintAdded(constraint);

        verify(historyRepository, times(1)).record(any());
    }

    @Test
    void onConstraintStatusChanged_recordsExactlyOneStatusChangedEntry() {
        UUID taskId = UUID.randomUUID();
        taskRepository.save(createTask(taskId, TaskStatus.ACTIVE, false, 0, 1));
        constraintRepository.save(newConstraint(taskId, "c-hist-chg", Constraint.ConstraintStatus.PENDING));

        service.onConstraintStatusChanged(taskId, "c-hist-chg", Constraint.ConstraintStatus.SATISFIED, null);

        ConstraintHistoryEntry entry = singleRecordedEntry();
        assertEquals(ConstraintHistoryAction.STATUS_CHANGED, entry.action());
        assertEquals(Constraint.ConstraintStatus.PENDING, entry.previousStatus());
        assertEquals(Constraint.ConstraintStatus.SATISFIED, entry.newStatus());
        assertEquals("source-ref", entry.sourceRef());
    }

    @Test
    void onConstraintStatusChanged_sameStatus_recordsNothing() {
        UUID taskId = UUID.randomUUID();
        taskRepository.save(createTask(taskId, TaskStatus.ACTIVE, false, 0, 1));
        constraintRepository.save(newConstraint(taskId, "c-hist-same", Constraint.ConstraintStatus.PENDING));

        service.onConstraintStatusChanged(taskId, "c-hist-same", Constraint.ConstraintStatus.PENDING, null);

        verify(historyRepository, never()).record(any());
    }

    @Test
    void onConstraintStatusChanged_betweenTwoClosedStatuses_recordsEntryButLeavesCounter() {
        UUID taskId = UUID.randomUUID();
        taskRepository.save(createTask(taskId, TaskStatus.ACTIVE, true, 0, 0));
        constraintRepository.save(newConstraint(taskId, "c-hist-closed", Constraint.ConstraintStatus.SATISFIED));

        service.onConstraintStatusChanged(taskId, "c-hist-closed", Constraint.ConstraintStatus.WAIVED, null);

        ConstraintHistoryEntry entry = singleRecordedEntry();
        assertEquals(Constraint.ConstraintStatus.SATISFIED, entry.previousStatus());
        assertEquals(Constraint.ConstraintStatus.WAIVED, entry.newStatus());
        assertEquals(0, taskRepository.findById(taskId).orElseThrow().getOpenConstraintCount());
    }

    @Test
    void onConstraintAdded_historyWriteFails_exceptionPropagates() {
        UUID taskId = UUID.randomUUID();
        taskRepository.save(createTask(taskId, TaskStatus.ACTIVE, true, 0, 0));
        doThrow(new IllegalStateException("history down")).when(historyRepository).record(any());

        assertThrows(IllegalStateException.class, () -> service.onConstraintAdded(
                newConstraint(taskId, "c-hist-fail", Constraint.ConstraintStatus.PENDING)));
    }

    @Test
    void onConstraintStatusChanged_historyWriteFails_exceptionPropagates() {
        UUID taskId = UUID.randomUUID();
        taskRepository.save(createTask(taskId, TaskStatus.ACTIVE, false, 0, 1));
        constraintRepository.save(newConstraint(taskId, "c-hist-fail2", Constraint.ConstraintStatus.PENDING));
        doThrow(new IllegalStateException("history down")).when(historyRepository).record(any());

        assertThrows(IllegalStateException.class, () -> service.onConstraintStatusChanged(
                taskId, "c-hist-fail2", Constraint.ConstraintStatus.SATISFIED, null));
    }

    private static Constraint newConstraint(
            UUID taskId,
            String constraintId,
            Constraint.ConstraintStatus status) {

        LocalDateTime now =
                LocalDateTime.now();

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

        UUID taskId =
                UUID.randomUUID();

        taskRepository.save(
                createTask(
                        taskId,
                        TaskStatus.ACTIVE,
                        true,
                        0,
                        0));

        TestTransactions.runCommitted(
                () -> service.onConstraintAdded(
                        newConstraint(
                                taskId,
                                "c-add",
                                Constraint.ConstraintStatus.PENDING)));

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

        UUID taskId =
                UUID.randomUUID();

        taskRepository.save(
                createTask(
                        taskId,
                        TaskStatus.ACTIVE,
                        false,
                        0,
                        1));

        constraintRepository.save(
                newConstraint(
                        taskId,
                        "c-sat",
                        Constraint.ConstraintStatus.PENDING));

        TestTransactions.runCommitted(
                () -> service.onConstraintStatusChanged(
                        taskId,
                        "c-sat",
                        Constraint.ConstraintStatus.SATISFIED,
                        null));

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

        UUID taskId =
                UUID.randomUUID();

        taskRepository.save(
                createTask(
                        taskId,
                        TaskStatus.ACTIVE,
                        false,
                        0,
                        1));

        constraintRepository.save(
                newConstraint(
                        taskId,
                        "c-same",
                        Constraint.ConstraintStatus.PENDING));

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

        UUID taskId =
                UUID.randomUUID();

        Fixture fixture =
                newFixture(
                        List.of(
                                buildTask(
                                        taskId,
                                        0,
                                        0,
                                        true)),
                        List.of());

        fixture.service()
                .onConstraintAdded(
                        newConstraint(
                                taskId,
                                "c-add2",
                                Constraint.ConstraintStatus.PENDING));

        Task task =
                fixture.taskRepo()
                        .findById(taskId)
                        .orElseThrow();

        assertEquals(
                1,
                task.getOpenConstraintCount());

        assertFalse(
                task.isReady());
    }

    @Test
    void onConstraintStatusChanged_closingLastConstraint_becomesReady() {

        UUID taskId =
                UUID.randomUUID();

        Fixture fixture =
                newFixture(
                        List.of(
                                buildTask(
                                        taskId,
                                        0,
                                        0,
                                        false)),
                        List.of());

        fixture.constraintRepo()
                .save(
                        newConstraint(
                                taskId,
                                "c-close2",
                                Constraint.ConstraintStatus.PENDING));

        fixture.service()
                .onConstraintStatusChanged(
                        taskId,
                        "c-close2",
                        Constraint.ConstraintStatus.SATISFIED,
                        null);

        Task task =
                fixture.taskRepo()
                        .findById(taskId)
                        .orElseThrow();

        assertEquals(
                0,
                task.getOpenConstraintCount());

        assertTrue(
                task.isReady());
    }

    @Test
    void onConstraintStatusChanged_reopeningConstraint_becomesNotReadyAgain() {

        UUID taskId =
                UUID.randomUUID();

        Fixture fixture =
                newFixture(
                        List.of(
                                buildTask(
                                        taskId,
                                        0,
                                        0,
                                        true)),
                        List.of());

        fixture.constraintRepo()
                .save(
                        newConstraint(
                                taskId,
                                "c-reopen",
                                Constraint.ConstraintStatus.SATISFIED));

        fixture.service()
                .onConstraintStatusChanged(
                        taskId,
                        "c-reopen",
                        Constraint.ConstraintStatus.PENDING,
                        null);

        Task task =
                fixture.taskRepo()
                        .findById(taskId)
                        .orElseThrow();

        assertEquals(
                1,
                task.getOpenConstraintCount());

        assertFalse(
                task.isReady());
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

    private record Fixture(
            TaskRepository taskRepo,
            TaskDependencyRepository dependencyRepo,
            ConstraintRepository constraintRepo,
            ConstraintEligibilityService service) {
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

        SchedulingUpdateService scheduling =
                new SchedulingUpdateService(event -> {});

        TaskCounterUpdater counterUpdater =
                new TaskCounterUpdater(
                        taskRepo,
                        new PriorityScoreService(
                                taskRepo,
                                dependencyRepo,
                                taskReadinessRepository,
                                scheduling,
                                new SchedulingPublishListener(
                                taskRepo,
                                scheduling)),
                        taskReadinessRepository);

        ConstraintEligibilityService constraintService =
                new ConstraintEligibilityService(
                        taskRepo,
                        constraintRepo,
                        mock(ConstraintHistoryRepository.class),
                        counterUpdater,
                        taskReadinessRepository,
                        TaskStateHistoryFixtures.newRecorder());

        return new Fixture(
                taskRepo,
                dependencyRepo,
                constraintRepo,
                constraintService);
    }

    private static Task buildTask(
            UUID id,
            int blockingCount,
            int openConstraintCount,
            boolean isReady) {

        return buildTask(
                id,
                blockingCount,
                openConstraintCount,
                isReady,
                TaskStatus.ACTIVE);
    }

    private static Task buildTask(
            UUID id,
            int blockingCount,
            int openConstraintCount,
            boolean isReady,
            TaskStatus status) {

        return TaskTestFixtures.defaultTask(id)
                .status(status)
                .isReady(isReady)
                .blockingCount(blockingCount)
                .openConstraintCount(openConstraintCount)
                .build();
    }
}