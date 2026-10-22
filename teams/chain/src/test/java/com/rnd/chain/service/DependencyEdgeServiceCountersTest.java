package com.rnd.chain.service;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskReadinessSnapshot;
import com.rnd.chain.dto.TaskTestFixtures;
import com.rnd.chain.exception.TaskNotFoundException;
import com.rnd.chain.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Unit tests for {@code updateCounters} / {@code adjustBlockingCount} with mocked collaborators. */
class DependencyEdgeServiceCountersTest {

    private final UUID taskId = UUID.randomUUID();
    private final TaskReadinessSnapshot before = new TaskReadinessSnapshot(taskId, 1, 0);
    private final TaskReadinessSnapshot after = new TaskReadinessSnapshot(taskId, 0, 0);

    private TaskRepository taskRepository;
    private PriorityScoreService priorityScoreService;
    private DependencyEdgeService service;

    @BeforeEach
    void setUp() {
        taskRepository = mock(TaskRepository.class);
        priorityScoreService = mock(PriorityScoreService.class);
        service = new DependencyEdgeService(taskRepository, null, null, priorityScoreService, null, null);
    }

    private Task stored(int blockingCount, int openConstraintCount) {
        return TaskTestFixtures.defaultTask(taskId)
                .blockingCount(blockingCount)
                .openConstraintCount(openConstraintCount)
                .build();
    }

    /** taskRepository.update applies the mutator to {@code stored}. */
    private void storedTaskIs(Task stored) {
        when(taskRepository.update(eq(taskId), any())).thenAnswer(invocation -> {
            UnaryOperator<Task> mutator = invocation.getArgument(1);
            return Optional.of(mutator.apply(stored));
        });
    }

    @Test
    void updateCounters_nullSnapshots_throw() {
        assertThatThrownBy(() -> service.updateCounters(taskId, UnaryOperator.identity(), null, after))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("beforeReadiness");
        assertThatThrownBy(() -> service.updateCounters(taskId, UnaryOperator.identity(), before, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("afterReadiness");

        verifyNoInteractions(taskRepository, priorityScoreService);
    }

    @Test
    void updateCounters_unknownTask_throwsTaskNotFound() {
        when(taskRepository.update(eq(taskId), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateCounters(taskId, UnaryOperator.identity(), before, after))
                .isInstanceOf(TaskNotFoundException.class);
        verifyNoInteractions(priorityScoreService);
    }

    @Test
    void updateCounters_appliesTheMutatorThenRecomputesWithTheGivenSnapshots() {
        Task stored = stored(1, 0);
        storedTaskIs(stored);
        Task recomputed = stored(0, 0);
        when(priorityScoreService.recomputePriorityScoreAndPublish(eq(taskId), same(stored), any(), eq(before), eq(after)))
                .thenReturn(recomputed);

        Task result = service.updateCounters(taskId, task -> task.withCounters(0, 0), before, after);

        assertThat(result).isSameAs(recomputed);
        verify(priorityScoreService).recomputePriorityScoreAndPublish(
                eq(taskId), same(stored), any(Task.class), eq(before), eq(after));
    }

    @Test
    void adjustBlockingCount_addsTheDelta() {
        Task stored = stored(1, 2);
        storedTaskIs(stored);

        service.adjustBlockingCount(taskId, false, 2, before, after);

        verify(priorityScoreService).recomputePriorityScoreAndPublish(
                eq(taskId), same(stored), any(Task.class), eq(before), eq(after));
        verify(taskRepository).update(eq(taskId), any());
    }

    @Test
    void adjustBlockingCount_neverGoesBelowZero_andKeepsOpenConstraintCount() {
        Task stored = stored(1, 2);
        Task[] written = new Task[1];
        when(taskRepository.update(eq(taskId), any())).thenAnswer(invocation -> {
            UnaryOperator<Task> mutator = invocation.getArgument(1);
            written[0] = mutator.apply(stored);
            return Optional.of(written[0]);
        });

        service.adjustBlockingCount(taskId, false, -5, before, after);

        assertThat(written[0].getBlockingCount()).isZero();
        assertThat(written[0].getOpenConstraintCount()).isEqualTo(2);
        assertThat(written[0].isReady()).isFalse();
    }

    @Test
    void adjustBlockingCount_whenTheDependencyIsAlreadyDone_ignoresTheDelta() {
        Task stored = stored(3, 0);
        Task[] written = new Task[1];
        when(taskRepository.update(eq(taskId), any())).thenAnswer(invocation -> {
            UnaryOperator<Task> mutator = invocation.getArgument(1);
            written[0] = mutator.apply(stored);
            return Optional.of(written[0]);
        });

        service.adjustBlockingCount(taskId, true, 4, before, after);

        assertThat(written[0]).isSameAs(stored);
    }
}
