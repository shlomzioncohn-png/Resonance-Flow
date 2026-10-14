
package com.rnd.chain.service;

import com.rnd.chain.dto.Constraint;
import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.repository.ConstraintRepository;
import com.rnd.chain.repository.TaskDependencyRepository;
import com.rnd.chain.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TaskStateReconciliationServiceTest {

    private TaskRepository taskRepository;
    private TaskDependencyRepository taskDependencyRepository;
    private ConstraintRepository constraintRepository;
    private TaskStateReconciliationService service;

    private Map<UUID, Task> tasks;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        taskRepository = mock(TaskRepository.class);
        taskDependencyRepository = mock(TaskDependencyRepository.class);
        constraintRepository = mock(ConstraintRepository.class);
        tasks = new HashMap<>();

        service = new TaskStateReconciliationService(
                taskRepository,
                taskDependencyRepository,
                constraintRepository
        );

        when(taskRepository.findById(any(UUID.class)))
                .thenAnswer(invocation ->
                        Optional.ofNullable(tasks.get(invocation.getArgument(0))));

        when(taskRepository.findAllById(anyList()))
                .thenAnswer(invocation -> {
                    List<UUID> ids = invocation.getArgument(0);
                    return ids.stream()
                            .map(tasks::get)
                            .filter(task -> task != null)
                            .collect(Collectors.toList());
                });

        // Simulates the repository's atomic update of an immutable Task.
        when(taskRepository.update(any(UUID.class), any()))
                .thenAnswer(invocation -> {
                    UUID id = invocation.getArgument(0);
                    UnaryOperator<Task> mutator = invocation.getArgument(1);

                    Task current = tasks.get(id);
                    if (current == null) {
                        return Optional.empty();
                    }

                    Task updated = mutator.apply(current);
                    tasks.put(id, updated);
                    return Optional.of(updated);
                });

        when(taskDependencyRepository.findByTaskId(any(UUID.class)))
                .thenReturn(Collections.emptyList());

        when(constraintRepository.findByTaskId(any(UUID.class)))
                .thenReturn(Collections.emptyList());
    }

    @Test
    void correctState_shouldNotUpdate() {
        Task task = createTask(TaskStatus.ACTIVE, true, 0, 0);

        assertFalse(service.reconcileTaskState(task.getId()));

        verify(taskRepository, never()).update(any(UUID.class), any());
    }

    @Test
    void incorrectBlockingCount_shouldBeCorrected() {
        Task task = createTask(TaskStatus.ACTIVE, true, 0, 0);
        Task blocker = createTask(TaskStatus.ACTIVE, false, 0, 0);

        setDependencies(task, blocker);

        assertTrue(service.reconcileTaskState(task.getId()));

        Task updated = tasks.get(task.getId());
        assertEquals(1, updated.getBlockingCount());
        assertFalse(updated.isReady());

        verify(taskRepository, times(1))
                .update(eq(task.getId()), any());
    }

    @Test
    void correction_shouldNotChangeUpdatedAt() {
        // Fixed past timestamp so a now()-based overwrite would be detected.
        LocalDateTime inboxUpdatedAt = LocalDateTime.now().minusDays(1);
        Task task = createTask(TaskStatus.ACTIVE, true, 0, 0, inboxUpdatedAt);
        Task blocker = createTask(TaskStatus.ACTIVE, false, 0, 0);

        setDependencies(task, blocker);

        assertTrue(service.reconcileTaskState(task.getId()));

        Task updated = tasks.get(task.getId());
        assertEquals(1, updated.getBlockingCount());
        assertFalse(updated.isReady());
        assertEquals(inboxUpdatedAt, updated.getUpdatedAt());
    }

    @Test
    void incorrectOpenConstraintCount_shouldBeCorrected() {
        Task task = createTask(TaskStatus.ACTIVE, true, 0, 0);

        when(constraintRepository.findByTaskId(task.getId()))
                .thenReturn(Collections.singletonList(
                        createConstraint(
                                task.getId(),
                                Constraint.ConstraintStatus.PENDING
                        )
                ));

        assertTrue(service.reconcileTaskState(task.getId()));

        Task updated = tasks.get(task.getId());
        assertEquals(1, updated.getOpenConstraintCount());
        assertFalse(updated.isReady());
    }

    @Test
    void incorrectReadyFlag_shouldBeCorrected() {
        Task task = createTask(TaskStatus.ACTIVE, false, 0, 0);

        assertTrue(service.reconcileTaskState(task.getId()));

        assertTrue(tasks.get(task.getId()).isReady());
    }

    @Test
    void multipleBlockers_shouldCountOnlyUnfinishedTasks() {
        Task task = createTask(TaskStatus.ACTIVE, true, 0, 0);
        Task active = createTask(TaskStatus.ACTIVE, false, 0, 0);
        Task done = createTask(TaskStatus.DONE, false, 0, 0);
        Task pending = createTask(TaskStatus.PENDING, false, 0, 0);

        setDependencies(task, active, done, pending);

        assertTrue(service.reconcileTaskState(task.getId()));

        Task updated = tasks.get(task.getId());
        assertEquals(2, updated.getBlockingCount());
        assertFalse(updated.isReady());
    }

    @Test
    void multipleConstraints_shouldCountOnlyPendingConstraints() {
        Task task = createTask(TaskStatus.ACTIVE, true, 0, 0);

        when(constraintRepository.findByTaskId(task.getId()))
                .thenReturn(Arrays.asList(
                        createConstraint(
                                task.getId(),
                                Constraint.ConstraintStatus.PENDING
                        ),
                        createConstraint(
                                task.getId(),
                                Constraint.ConstraintStatus.SATISFIED
                        ),
                        createConstraint(
                                task.getId(),
                                Constraint.ConstraintStatus.WAIVED
                        )
                ));

        assertTrue(service.reconcileTaskState(task.getId()));

        Task updated = tasks.get(task.getId());
        assertEquals(1, updated.getOpenConstraintCount());
        assertFalse(updated.isReady());
    }

    @Test
    void dependencyAndConstraint_shouldReconcileBoth() {
        Task task = createTask(TaskStatus.ACTIVE, true, 0, 0);
        Task blocker = createTask(TaskStatus.ACTIVE, false, 0, 0);

        setDependencies(task, blocker);

        when(constraintRepository.findByTaskId(task.getId()))
                .thenReturn(Collections.singletonList(
                        createConstraint(
                                task.getId(),
                                Constraint.ConstraintStatus.PENDING
                        )
                ));

        assertTrue(service.reconcileTaskState(task.getId()));

        Task updated = tasks.get(task.getId());
        assertEquals(1, updated.getBlockingCount());
        assertEquals(1, updated.getOpenConstraintCount());
        assertFalse(updated.isReady());
    }

    @Test
    void runningTwice_shouldBeIdempotent() {
        Task task = createTask(TaskStatus.ACTIVE, true, 0, 0);
        Task blocker = createTask(TaskStatus.ACTIVE, false, 0, 0);

        setDependencies(task, blocker);

        assertTrue(service.reconcileTaskState(task.getId()));
        assertFalse(service.reconcileTaskState(task.getId()));

        assertEquals(1, tasks.get(task.getId()).getBlockingCount());

        verify(taskRepository, times(1))
                .update(eq(task.getId()), any());
    }

    @Test
    void batch_shouldContinueAfterMissingTask() {
        Task first = createTask(TaskStatus.ACTIVE, true, 0, 0);
        UUID missingId = UUID.randomUUID();
        Task third = createTask(TaskStatus.ACTIVE, true, 0, 0);

        service.reconcileTasks(
                Arrays.asList(first.getId(), missingId, third.getId())
        );

        verify(taskRepository).findById(first.getId());
        verify(taskRepository).findById(missingId);
        verify(taskRepository).findById(third.getId());
    }

    private Task createTask(
            TaskStatus status,
            boolean isReady,
            int blockingCount,
            int openConstraintCount) {
        return createTask(status, isReady, blockingCount, openConstraintCount, LocalDateTime.now());
    }

    private Task createTask(
            TaskStatus status,
            boolean isReady,
            int blockingCount,
            int openConstraintCount,
            LocalDateTime updatedAt) {

        Task task = Task.builder()
                .id(UUID.randomUUID())
                .status(status)
                .isReady(isReady)
                .blockingCount(blockingCount)
                .openConstraintCount(openConstraintCount)
                .createdAt(LocalDateTime.now())
                .updatedAt(updatedAt)
                .build();

        tasks.put(task.getId(), task);
        return task;
    }

    private void setDependencies(Task task, Task... blockers) {
        List<TaskDependency> dependencies = Arrays.stream(blockers)
                .map(blocker ->
                        new TaskDependency(task.getId(), blocker.getId()))
                .collect(Collectors.toList());

        when(taskDependencyRepository.findByTaskId(task.getId()))
                .thenReturn(dependencies);
    }

    private Constraint createConstraint(
            UUID taskId,
            Constraint.ConstraintStatus status) {

        return new Constraint(
                UUID.randomUUID().toString(),
                taskId,
                Constraint.ConstraintType.APPROVAL_NEEDED,
                status,
                Collections.emptyMap(),
                "test",
                LocalDateTime.now(),
                LocalDateTime.now()
        );
    }
}