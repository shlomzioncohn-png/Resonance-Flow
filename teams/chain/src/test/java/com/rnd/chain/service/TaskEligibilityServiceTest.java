package com.rnd.chain.service;

import com.rnd.chain.dto.Constraint;
import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.event.SchedulingUpdatePublisher;
import com.rnd.chain.event.TaskSchedulingUpdatedEvent;
import com.rnd.chain.exception.DependencyNotFoundException;
import com.rnd.chain.exception.DuplicateDependencyException;
import com.rnd.chain.exception.InvalidDependencyException;
import com.rnd.chain.exception.TaskNotFoundException;
import com.rnd.chain.repository.InMemoryTaskDependencyRepository;
import com.rnd.chain.repository.InMemoryTaskRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TaskEligibilityServiceTest {

    private InMemoryTaskRepository taskRepository;
    private InMemoryTaskDependencyRepository dependencyRepository;

    private RecordingSchedulingUpdatePublisher publisher;

    private TaskEligibilityService service;

    @BeforeEach
    void setUp() {

        taskRepository =
                new InMemoryTaskRepository(List.of());

        dependencyRepository =
                new InMemoryTaskDependencyRepository(List.of());

        publisher =
                new RecordingSchedulingUpdatePublisher();

        SchedulingUpdateService schedulingUpdateService =
                new SchedulingUpdateService(publisher);

        service =
                new TaskEligibilityService(
                        taskRepository,
                        dependencyRepository,
                        schedulingUpdateService);
    }

    @Test
    void addDependency_shouldIncreaseBlockingCountAndMakeTaskNotReady() {

        UUID taskId = UUID.randomUUID();
        UUID dependencyId = UUID.randomUUID();

        taskRepository.save(
                createTask(
                        taskId,
                        TaskStatus.ACTIVE,
                        true,
                        0,
                        0));

        taskRepository.save(
                createTask(
                        dependencyId,
                        TaskStatus.ACTIVE,
                        true,
                        0,
                        0));

        service.addDependency(
                taskId,
                dependencyId);

        Task updated =
                taskRepository.findById(taskId)
                        .orElseThrow();

        assertEquals(
                1,
                updated.getBlockingCount());

        assertFalse(
                updated.isReady());

        assertEquals(
                1,
                publisher.getEvents().size());

        assertEquals(
                taskId,
                publisher.getEvents()
                        .get(0)
                        .getTaskId());

        assertFalse(
                publisher.getEvents()
                        .get(0)
                        .isAvailable());
    }

    @Test
    void addDependency_whenDependencyAlreadyDone_shouldNotBlockTask() {

        UUID taskId = UUID.randomUUID();
        UUID dependencyId = UUID.randomUUID();

        taskRepository.save(
                createTask(
                        taskId,
                        TaskStatus.ACTIVE,
                        true,
                        0,
                        0));

        taskRepository.save(
                createTask(
                        dependencyId,
                        TaskStatus.DONE,
                        true,
                        0,
                        0));

        service.addDependency(
                taskId,
                dependencyId);

        Task updated =
                taskRepository.findById(taskId)
                        .orElseThrow();

        assertEquals(
                0,
                updated.getBlockingCount());

        assertTrue(
                updated.isReady());

        assertTrue(
                publisher.getEvents().isEmpty());
    }

    @Test
    void addDependency_whenDuplicate_shouldThrowDuplicateDependencyException() {

        UUID taskId = UUID.randomUUID();
        UUID dependencyId = UUID.randomUUID();

        taskRepository.save(
                createTask(
                        taskId,
                        TaskStatus.ACTIVE,
                        true,
                        0,
                        0));

        taskRepository.save(
                createTask(
                        dependencyId,
                        TaskStatus.ACTIVE,
                        true,
                        0,
                        0));

        service.addDependency(
                taskId,
                dependencyId);

        assertThrows(
                DuplicateDependencyException.class,
                () -> service.addDependency(
                        taskId,
                        dependencyId));

        Task updated =
                taskRepository.findById(taskId)
                        .orElseThrow();

        assertEquals(
                1,
                updated.getBlockingCount());
    }

    @Test
    void addDependency_whenCycleWouldBeCreated_shouldThrowInvalidDependencyException() {

        UUID taskA = UUID.randomUUID();
        UUID taskB = UUID.randomUUID();
        UUID taskC = UUID.randomUUID();

        taskRepository.save(
                createTask(
                        taskA,
                        TaskStatus.ACTIVE,
                        true,
                        0,
                        0));

        taskRepository.save(
                createTask(
                        taskB,
                        TaskStatus.ACTIVE,
                        true,
                        0,
                        0));

        taskRepository.save(
                createTask(
                        taskC,
                        TaskStatus.ACTIVE,
                        true,
                        0,
                        0));

        service.addDependency(
                taskA,
                taskB);

        service.addDependency(
                taskB,
                taskC);

        assertThrows(
                InvalidDependencyException.class,
                () -> service.addDependency(
                        taskC,
                        taskA));

        assertTrue(
                dependencyRepository
                        .findByTaskId(taskC)
                        .isEmpty());
    }

    @Test
    void removeDependency_shouldDecreaseBlockingCountAndMakeTaskReady() {

        UUID taskId = UUID.randomUUID();
        UUID dependencyId = UUID.randomUUID();

        taskRepository.save(
                createTask(
                        taskId,
                        TaskStatus.ACTIVE,
                        true,
                        0,
                        0));

        taskRepository.save(
                createTask(
                        dependencyId,
                        TaskStatus.ACTIVE,
                        true,
                        0,
                        0));

        service.addDependency(
                taskId,
                dependencyId);

        publisher.clear();

        service.removeDependency(
                taskId,
                dependencyId);

        Task updated =
                taskRepository.findById(taskId)
                        .orElseThrow();

        assertEquals(
                0,
                updated.getBlockingCount());

        assertTrue(
                updated.isReady());

        assertEquals(
                1,
                publisher.getEvents().size());

        assertTrue(
                publisher.getEvents()
                        .get(0)
                        .isAvailable());
    }

    @Test
    void removeDependency_whenDependencyDoesNotExist_shouldThrow() {

        UUID taskId = UUID.randomUUID();
        UUID dependencyId = UUID.randomUUID();

        taskRepository.save(
                createTask(
                        taskId,
                        TaskStatus.ACTIVE,
                        true,
                        0,
                        0));

        taskRepository.save(
                createTask(
                        dependencyId,
                        TaskStatus.ACTIVE,
                        true,
                        0,
                        0));

        assertThrows(
                DependencyNotFoundException.class,
                () -> service.removeDependency(
                        taskId,
                        dependencyId));
    }

    @Test
    void onTaskStatusChanged_whenDependencyBecomesDone_shouldUnblockDependent() {

        UUID taskId = UUID.randomUUID();
        UUID dependencyId = UUID.randomUUID();

        taskRepository.save(
                createTask(
                        taskId,
                        TaskStatus.ACTIVE,
                        true,
                        0,
                        0));

        taskRepository.save(
                createTask(
                        dependencyId,
                        TaskStatus.ACTIVE,
                        true,
                        0,
                        0));

        service.addDependency(
                taskId,
                dependencyId);

        publisher.clear();

        service.onTaskStatusChanged(
                dependencyId,
                TaskStatus.DONE);

        Task updated =
                taskRepository.findById(taskId)
                        .orElseThrow();

        assertEquals(
                0,
                updated.getBlockingCount());

        assertTrue(
                updated.isReady());

        assertEquals(
                1,
                publisher.getEvents().size());
    }

    @Test
    void onTaskStatusChanged_whenDependencyReopens_shouldBlockDependentAgain() {

        UUID taskId = UUID.randomUUID();
        UUID dependencyId = UUID.randomUUID();

        taskRepository.save(
                createTask(
                        taskId,
                        TaskStatus.ACTIVE,
                        true,
                        0,
                        0));

        taskRepository.save(
                createTask(
                        dependencyId,
                        TaskStatus.DONE,
                        true,
                        0,
                        0));

        service.addDependency(
                taskId,
                dependencyId);

        publisher.clear();

        service.onTaskStatusChanged(
                dependencyId,
                TaskStatus.ACTIVE);

        Task updated =
                taskRepository.findById(taskId)
                        .orElseThrow();

        assertEquals(
                1,
                updated.getBlockingCount());

        assertFalse(
                updated.isReady());

        assertEquals(
                1,
                publisher.getEvents().size());
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

        service.onConstraintAdded(taskId);

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

        service.onConstraintStatusChanged(
                taskId,
                Constraint.ConstraintStatus.PENDING,
                Constraint.ConstraintStatus.SATISFIED);

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

        service.onConstraintStatusChanged(
                taskId,
                Constraint.ConstraintStatus.PENDING,
                Constraint.ConstraintStatus.PENDING);

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
    void getTaskEligibility_whenTaskDoesNotExist_shouldThrowTaskNotFoundException() {

        UUID missingTaskId =
                UUID.randomUUID();

        assertThrows(
                TaskNotFoundException.class,
                () -> service.getTaskEligibility(
                        missingTaskId));
    }

    @Test
    void recalculateTaskEligibility_shouldFixIncorrectReadyState() {

        UUID taskId = UUID.randomUUID();

        taskRepository.save(
                createTask(
                        taskId,
                        TaskStatus.ACTIVE,
                        true,
                        1,
                        0));

        service.recalculateTaskEligibility(
                taskId);

        Task updated =
                taskRepository.findById(taskId)
                        .orElseThrow();

        assertFalse(
                updated.isReady());

        assertEquals(
                1,
                updated.getBlockingCount());

        assertEquals(
                1,
                publisher.getEvents().size());
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
}