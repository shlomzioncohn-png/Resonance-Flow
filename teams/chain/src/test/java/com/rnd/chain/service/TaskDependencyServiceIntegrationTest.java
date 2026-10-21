package com.rnd.chain.service;

import com.rnd.chain.testsupport.TaskStateHistoryFixtures;
import com.rnd.chain.testsupport.PgRepos;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.events.SchedulingPublishListener;
import com.rnd.chain.events.SchedulingUpdatePublisher;
import com.rnd.chain.events.TaskSchedulingUpdatedEvent;
import com.rnd.chain.repository.TaskDependencyRepository;
import com.rnd.chain.repository.TaskReadinessRepository;
import com.rnd.chain.repository.TaskRepository;
import com.rnd.chain.testsupport.IncrementingClock;
import com.rnd.chain.testsupport.TestTransactions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Ported from the in-memory version; runs against the real Postgres repositories (rolled back per test). */
@Tag("postgres")
@SpringBootTest
@Transactional
class TaskDependencyServiceIntegrationTest {

    private static final UUID TENANT_ID = UUID.randomUUID();

    private UUID taskId;
    private UUID dependencyTaskId;

    private TaskRepository taskRepository;
    private TaskDependencyRepository dependencyRepository;

    private RecordingSchedulingUpdatePublisher schedulingPublisher;

    private TaskDependencyService taskDependencyService;

    @Autowired
    private TaskReadinessRepository taskReadinessRepository;

    @BeforeEach
    void setUp() {

        taskId = UUID.randomUUID();
        dependencyTaskId = UUID.randomUUID();

        Task task = createTask(
                taskId,
                TaskStatus.ACTIVE,
                true);

        Task dependencyTask = createTask(
                dependencyTaskId,
                TaskStatus.ACTIVE,
                true);

        taskRepository =
                PgRepos.tasks(
                        List.of(task, dependencyTask));

        dependencyRepository =
                PgRepos.dependencies(
                        List.of());

        schedulingPublisher =
                new RecordingSchedulingUpdatePublisher();

        SchedulingUpdateService schedulingUpdateService =
                new SchedulingUpdateService(
                        schedulingPublisher);

        TaskCounterUpdater counterUpdater =
                new TaskCounterUpdater(
                        taskRepository,
                        dependencyRepository,
                        new PriorityScoreService(),
                        new SchedulingPublishListener(
                                taskRepository,
                                schedulingUpdateService),
                        taskReadinessRepository);

        DependencyEdgeService dependencyEdgeService =
                new DependencyEdgeService(
                        dependencyRepository,
                        PgRepos.graphLock(),
                        counterUpdater,
                        TaskStateHistoryFixtures.newRecorder(),
                        taskReadinessRepository);

        taskDependencyService =
                new TaskDependencyService(
                        taskRepository,
                        dependencyRepository,
                        PgRepos.history(),
                        PgRepos.transactionRunner(),
                        new IncrementingClock(),
                        dependencyEdgeService);
    }

    @Test
    void addDependency_shouldCreateDependencyAndUpdateEligibility() {

        TaskDependency created =
                TestTransactions.runCommitted(() -> taskDependencyService.addDependency(
                        taskId,
                        dependencyTaskId));

        assertNotNull(created);

        List<TaskDependency> dependencies =
                dependencyRepository.findByTaskId(taskId);

        assertEquals(
                1,
                dependencies.size());

        assertEquals(
                dependencyTaskId,
                dependencies.get(0)
                        .dependsOnTaskId());

        Task updatedTask =
                taskRepository.findById(taskId)
                        .orElseThrow();

        assertEquals(
                1,
                updatedTask.getBlockingCount());

        assertFalse(
                updatedTask.isReady());

        assertEquals(
                1,
                schedulingPublisher
                        .getPublishedEvents()
                        .size());

        TaskSchedulingUpdatedEvent event =
                schedulingPublisher
                        .getPublishedEvents()
                        .get(0);

        assertEquals(
                taskId,
                event.getTaskId());

        assertFalse(
                event.isAvailable());
    }

    @Test
    void removeDependency_shouldRemoveDependencyAndUpdateEligibility() {

        taskDependencyService.addDependency(
                taskId,
                dependencyTaskId);

        Task blockedTask =
                taskRepository.findById(taskId)
                        .orElseThrow();

        assertEquals(
                1,
                blockedTask.getBlockingCount());

        assertFalse(
                blockedTask.isReady());

        schedulingPublisher.clear();

        TestTransactions.runCommitted(() -> taskDependencyService.removeDependency(
                taskId,
                dependencyTaskId));

        assertTrue(
                dependencyRepository
                        .findByTaskId(taskId)
                        .isEmpty());

        Task updatedTask =
                taskRepository.findById(taskId)
                        .orElseThrow();

        assertEquals(
                0,
                updatedTask.getBlockingCount());

        assertTrue(
                updatedTask.isReady());

        assertEquals(
                1,
                schedulingPublisher
                        .getPublishedEvents()
                        .size());

        TaskSchedulingUpdatedEvent event =
                schedulingPublisher
                        .getPublishedEvents()
                        .get(0);

        assertEquals(
                taskId,
                event.getTaskId());

        assertTrue(
                event.isAvailable());
    }

    @Test
    void addDependency_whenDependencyAlreadyDone_shouldNotIncreaseBlockingCount() {

        UUID doneTaskId =
                UUID.randomUUID();

        Task doneTask =
                createTask(
                        doneTaskId,
                        TaskStatus.DONE,
                        true);

        taskRepository.save(doneTask);

        taskDependencyService.addDependency(
                taskId,
                doneTaskId);

        Task updatedTask =
                taskRepository.findById(taskId)
                        .orElseThrow();

        assertEquals(
                0,
                updatedTask.getBlockingCount());

        assertTrue(
                updatedTask.isReady());

        assertTrue(
                schedulingPublisher
                        .getPublishedEvents()
                        .isEmpty());
    }

    @Test
    void addDependency_whenCycleWouldBeCreated_shouldThrowException() {

        UUID taskB = UUID.randomUUID();
        UUID taskC = UUID.randomUUID();

        taskRepository.save(
                createTask(
                        taskB,
                        TaskStatus.ACTIVE,
                        true));

        taskRepository.save(
                createTask(
                        taskC,
                        TaskStatus.ACTIVE,
                        true));

        taskDependencyService.addDependency(
                taskId,
                taskB);

        taskDependencyService.addDependency(
                taskB,
                taskC);

        assertThrows(
                RuntimeException.class,
                () -> taskDependencyService.addDependency(
                        taskC,
                        taskId));

        assertTrue(
                dependencyRepository
                        .findByTaskId(taskC)
                        .isEmpty());
    }

    private Task createTask(
            UUID id,
            TaskStatus status,
            boolean ready) {

        LocalDateTime now =
                LocalDateTime.now();

        return Task.builder()
                .id(id)
                .parentTaskId(null)
                .tenantId(TENANT_ID)
                .status(status)
                .isReady(ready)
                .blockingCount(0)
                .openConstraintCount(0)
                .estimatedDurationMin(null)
                .score(null)
                .createdAt(now)
                .updatedAt(now)
                .deletedAt(null)
                .build();
    }

    private static class RecordingSchedulingUpdatePublisher
            implements SchedulingUpdatePublisher {

        private final List<TaskSchedulingUpdatedEvent>
                publishedEvents =
                new ArrayList<>();

        @Override
        public void publish(
                TaskSchedulingUpdatedEvent event) {

            publishedEvents.add(event);
        }

        public List<TaskSchedulingUpdatedEvent>
        getPublishedEvents() {

            return publishedEvents;
        }

        public void clear() {
            publishedEvents.clear();
        }
    }
}
