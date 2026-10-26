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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Unit tests for {@code recomputePriority} with mocked collaborators. */
class DependencyEdgeServiceRecomputePriorityTest {

    private final UUID taskId = UUID.randomUUID();
    private final TaskReadinessSnapshot before = new TaskReadinessSnapshot(taskId, UUID.randomUUID(), 1, 0);
    private final TaskReadinessSnapshot after = new TaskReadinessSnapshot(taskId, UUID.randomUUID(), 0, 0);

    private TaskRepository taskRepository;
    private PriorityScoreService priorityScoreService;
    private DependencyEdgeService service;

    @BeforeEach
    void setUp() {
        taskRepository = mock(TaskRepository.class);
        priorityScoreService = mock(PriorityScoreService.class);
        service = new DependencyEdgeService(taskRepository, null, null, priorityScoreService, null, null);
    }

    @Test
    void nullSnapshots_throw() {
        assertThatThrownBy(() -> service.recomputePriority(taskId, null, after))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("beforeReadiness");
        assertThatThrownBy(() -> service.recomputePriority(taskId, before, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("afterReadiness");

        verifyNoInteractions(taskRepository, priorityScoreService);
    }

    @Test
    void unknownTask_throwsTaskNotFound() {
        when(taskRepository.update(eq(taskId), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recomputePriority(taskId, before, after))
                .isInstanceOf(TaskNotFoundException.class);
        verifyNoInteractions(priorityScoreService);
    }

    @Test
    void locksTheTaskThenRecomputesWithTheGivenSnapshots() {
        Task stored = TaskTestFixtures.defaultTask(taskId).build();
        Task recomputed = TaskTestFixtures.defaultTask(taskId).priorityScore(5.0).build();
        when(taskRepository.update(eq(taskId), any())).thenReturn(Optional.of(stored));
        when(priorityScoreService.recomputePriorityScoreAndPublish(
                eq(taskId), same(stored), same(stored), eq(before), eq(after)))
                .thenReturn(recomputed);

        Task result = service.recomputePriority(taskId, before, after);

        assertThat(result).isSameAs(recomputed);
        verify(taskRepository).update(eq(taskId), any());
    }
}
