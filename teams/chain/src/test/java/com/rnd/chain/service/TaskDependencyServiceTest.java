package com.rnd.chain.service;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.exception.DependencyNotFoundException;
import com.rnd.chain.exception.DuplicateDependencyException;
import com.rnd.chain.exception.InvalidDependencyException;
import com.rnd.chain.exception.TaskNotFoundException;
import com.rnd.chain.repository.InMemoryTaskDependencyRepository;
import com.rnd.chain.repository.InMemoryTaskRepository;
import com.rnd.chain.repository.TaskDependencyRepository;
import com.rnd.chain.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskDependencyServiceTest {

    private UUID taskA;
    private UUID taskB;
    private UUID taskC;
    private UUID taskD;
    private UUID unknownTaskId;

    private TaskDependencyService service;

    @BeforeEach
    void setUp() {
        taskA = UUID.randomUUID();
        taskB = UUID.randomUUID();
        taskC = UUID.randomUUID();
        taskD = UUID.randomUUID();
        unknownTaskId = UUID.randomUUID();

        TaskRepository taskRepository = new InMemoryTaskRepository(
                List.of(newTask(taskA), newTask(taskB), newTask(taskC), newTask(taskD)));
        TaskDependencyRepository taskDependencyRepository =
                new InMemoryTaskDependencyRepository(List.of());

        service = new TaskDependencyService(taskRepository, taskDependencyRepository);
    }

    // --- addDependency -------------------------------------------------

    @Test
    void addDependency_createsEdge_retrievableAsDependencyAndDependent() {
        service.addDependency(taskA, taskB);

        assertThat(service.getDependencies(taskA)).containsExactly(taskB);
        assertThat(service.getDependents(taskB)).containsExactly(taskA);
    }

    @Test
    void addDependency_rejectsSelfDependency() {
        assertThatThrownBy(() -> service.addDependency(taskA, taskA))
                .isInstanceOf(InvalidDependencyException.class);
    }

    @Test
    void addDependency_rejectsWhenTaskDoesNotExist() {
        assertThatThrownBy(() -> service.addDependency(unknownTaskId, taskB))
                .isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    void addDependency_rejectsWhenDependsOnTaskDoesNotExist() {
        assertThatThrownBy(() -> service.addDependency(taskA, unknownTaskId))
                .isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    void addDependency_rejectsDuplicateEdge() {
        service.addDependency(taskA, taskB);

        assertThatThrownBy(() -> service.addDependency(taskA, taskB))
                .isInstanceOf(DuplicateDependencyException.class);
    }

    // --- removeDependency ------------------------------------------------

    @Test
    void removeDependency_removesEdge() {
        service.addDependency(taskA, taskB);

        service.removeDependency(taskA, taskB);

        assertThat(service.getDependencies(taskA)).isEmpty();
        assertThat(service.getDependents(taskB)).isEmpty();
    }

    @Test
    void removeDependency_rejectsWhenEdgeDoesNotExist() {
        assertThatThrownBy(() -> service.removeDependency(taskA, taskB))
                .isInstanceOf(DependencyNotFoundException.class);
    }

    // --- getDependencies / getDependents ----------------------------------

    @Test
    void getDependencies_rejectsWhenTaskDoesNotExist() {
        assertThatThrownBy(() -> service.getDependencies(unknownTaskId))
                .isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    void getDependents_rejectsWhenTaskDoesNotExist() {
        assertThatThrownBy(() -> service.getDependents(unknownTaskId))
                .isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    void getDependencies_isEmpty_forTaskWithNoDependencies() {
        assertThat(service.getDependencies(taskA)).isEmpty();
    }

    @Test
    void getDependents_isEmpty_forTaskNothingDependsOn() {
        assertThat(service.getDependents(taskA)).isEmpty();
    }

    /**
     * Matches the example from issue #66:
     * A depends on B, A depends on C, D depends on A
     * -> getDependencies(A) = [B, C], getDependents(A) = [D]
     */
    @Test
    void matchesDependencyGraphExampleFromIssue() {
        service.addDependency(taskA, taskB);
        service.addDependency(taskA, taskC);
        service.addDependency(taskD, taskA);

        assertThat(service.getDependencies(taskA)).containsExactlyInAnyOrder(taskB, taskC);
        assertThat(service.getDependents(taskA)).containsExactly(taskD);
    }

    private static Task newTask(UUID id) {
        return new Task(id, null, "owner", TaskStatus.ACTIVE, true, 0, 0,
                null, null, LocalDateTime.now(), LocalDateTime.now(), null);
    }
}
