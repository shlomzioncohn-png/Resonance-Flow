package com.rnd.chain.service;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskDependency;
import com.rnd.chain.dto.TaskReadinessSnapshot;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.dto.TaskTestFixtures;
import com.rnd.chain.events.SchedulingPublishListener;
import com.rnd.chain.exception.InvalidRequestException;
import com.rnd.chain.exception.TaskNotFoundException;
import com.rnd.chain.repository.TaskDependencyRepository;
import com.rnd.chain.repository.TaskReadinessRepository;
import com.rnd.chain.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@code PriorityScoreService.recomputePriorityScoreAndPublish} / {@code onScoreProposed}
 * with mocked collaborators; the end-to-end behaviour against Postgres is in
 * {@code PriorityScoreServicePostgresTest} and {@code TaskEligibilityServiceTest}.
 */
class PriorityScoreServiceRecomputeTest {

    // compute() is pure, so the collaborators are not needed to derive expected scores.
    private static final PriorityScoreService PURE = new PriorityScoreService(null, null, null, null, null);

    private final UUID taskId = UUID.randomUUID();
    private final TaskReadinessSnapshot ready = new TaskReadinessSnapshot(taskId, 0, 0);
    private final TaskReadinessSnapshot blocked = new TaskReadinessSnapshot(taskId, 1, 0);

    private TaskRepository taskRepository;
    private TaskDependencyRepository dependencyRepository;
    private TaskReadinessRepository readinessRepository;
    private SchedulingUpdateService schedulingUpdateService;
    private SchedulingPublishListener listener;
    private PriorityScoreService service;

    @BeforeEach
    void setUp() {
        taskRepository = mock(TaskRepository.class);
        dependencyRepository = mock(TaskDependencyRepository.class);
        readinessRepository = mock(TaskReadinessRepository.class);
        schedulingUpdateService = mock(SchedulingUpdateService.class);
        listener = mock(SchedulingPublishListener.class);
        service = new PriorityScoreService(
                taskRepository, dependencyRepository, readinessRepository, schedulingUpdateService, listener);
    }

    private Task.Builder task(UUID id) {
        return TaskTestFixtures.defaultTask(id);
    }

    private void noEdges() {
        when(dependencyRepository.findByTaskId(taskId)).thenReturn(List.of());
        when(dependencyRepository.findByDependsOnTaskId(taskId)).thenReturn(List.of());
    }

    /** Makes taskRepository.update apply the mutator to {@code stored} and return the result. */
    private void storedTaskIs(Task stored) {
        when(taskRepository.update(eq(stored.getId()), any())).thenAnswer(invocation -> {
            UnaryOperator<Task> mutator = invocation.getArgument(1);
            return Optional.of(mutator.apply(stored));
        });
    }

    @Test
    void recompute_nullBeforeReadiness_throws() {
        Task current = task(taskId).build();

        assertThatThrownBy(() -> service.recomputePriorityScoreAndPublish(taskId, current, current, null, ready))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("beforeReadiness");
    }

    @Test
    void recompute_nullAfterReadiness_throws() {
        Task current = task(taskId).build();

        assertThatThrownBy(() -> service.recomputePriorityScoreAndPublish(taskId, current, current, ready, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("afterReadiness");
    }

    @Test
    void recompute_softDeletedTask_returnsCurrentAndTouchesNothing() {
        Task deleted = task(taskId).score(0.8).deletedAt(LocalDateTime.now()).build();

        Task result = service.recomputePriorityScoreAndPublish(taskId, deleted, deleted, ready, blocked);

        assertThat(result).isSameAs(deleted);
        verifyNoInteractions(taskRepository, dependencyRepository, schedulingUpdateService);
    }

    @Test
    void recompute_noMindScore_leavesNullPriorityScoreAndPublishesNothing() {
        Task current = task(taskId).build();
        noEdges();

        Task result = service.recomputePriorityScoreAndPublish(taskId, current, current, ready, ready);

        assertThat(result).isSameAs(current);
        verify(taskRepository, never()).update(any(), any());
        verify(schedulingUpdateService, never()).requestPublishAfterCommit(any(), any());
    }

    @Test
    void recompute_changedPriority_persistsAndRequestsPublish() {
        Task old = task(taskId).score(0.8).confidence(1.0).build();
        storedTaskIs(old);
        noEdges();

        Task result = service.recomputePriorityScoreAndPublish(taskId, old, old, ready, ready);

        assertThat(result.getPriorityScore()).isEqualTo(PURE.compute(0.8, 1.0, 0, 0, 0));
        verify(schedulingUpdateService).requestPublishAfterCommit(taskId, listener);
    }

    @Test
    void recompute_unchangedPriority_doesNotUpdateOrPublish() {
        Double expected = PURE.compute(0.8, 1.0, 0, 0, 0);
        Task current = task(taskId).score(0.8).confidence(1.0).priorityScore(expected).build();
        noEdges();

        Task result = service.recomputePriorityScoreAndPublish(taskId, current, current, ready, ready);

        assertThat(result).isSameAs(current);
        verify(taskRepository, never()).update(any(), any());
        verify(schedulingUpdateService, never()).requestPublishAfterCommit(any(), any());
    }

    @Test
    void recompute_availabilityFlipWithUnchangedPriority_requestsPublish() {
        TaskReadinessSnapshot afterConstraintOpened = new TaskReadinessSnapshot(taskId, 0, 1);
        Double expected = PURE.compute(0.8, 1.0, 0, 1, 0);
        Task current = task(taskId).score(0.8).confidence(1.0).priorityScore(expected).build();
        noEdges();

        service.recomputePriorityScoreAndPublish(taskId, current, current, ready, afterConstraintOpened);

        verify(taskRepository, never()).update(any(), any());
        verify(schedulingUpdateService).requestPublishAfterCommit(taskId, listener);
    }

    @Test
    void recompute_nullOldTask_persistsButDoesNotRequestPublish() {
        Task current = task(taskId).score(0.8).confidence(1.0).build();
        storedTaskIs(current);
        noEdges();

        Task result = service.recomputePriorityScoreAndPublish(taskId, null, current, ready, ready);

        assertThat(result.getPriorityScore()).isNotNull();
        verify(schedulingUpdateService, never()).requestPublishAfterCommit(any(), any());
    }

    @Test
    void recompute_taskVanishesDuringUpdate_throwsTaskNotFound() {
        Task current = task(taskId).score(0.8).confidence(1.0).build();
        noEdges();
        when(taskRepository.update(eq(taskId), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.recomputePriorityScoreAndPublish(taskId, current, current, ready, ready))
                .isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    void recompute_usesAfterReadinessOpenConstraintCountNotTheStoredColumn() {
        Task current = task(taskId).score(0.8).confidence(1.0).build();
        storedTaskIs(current);
        noEdges();
        TaskReadinessSnapshot twoOpen = new TaskReadinessSnapshot(taskId, 0, 2);

        Task result = service.recomputePriorityScoreAndPublish(taskId, current, current, twoOpen, twoOpen);

        assertThat(result.getPriorityScore()).isEqualTo(PURE.compute(0.8, 1.0, 0, 2, 0));
    }

    @Test
    void recompute_blockingSum_countsNotDoneAndMissingBlockers_withNullConfidenceAsFull() {
        UUID open = UUID.randomUUID();
        UUID done = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        UUID nullConfidence = UUID.randomUUID();
        Task current = task(taskId).score(0.8).confidence(1.0).build();
        storedTaskIs(current);
        when(dependencyRepository.findByTaskId(taskId)).thenReturn(List.of(
                new TaskDependency(taskId, open, 0.5),
                new TaskDependency(taskId, done, 0.9),
                new TaskDependency(taskId, missing, 0.25),
                new TaskDependency(taskId, nullConfidence, null)));
        when(dependencyRepository.findByDependsOnTaskId(taskId)).thenReturn(List.of());
        when(taskRepository.findById(open)).thenReturn(Optional.of(task(open).status(TaskStatus.ACTIVE).build()));
        when(taskRepository.findById(done)).thenReturn(Optional.of(task(done).status(TaskStatus.DONE).build()));
        when(taskRepository.findById(missing)).thenReturn(Optional.empty());
        when(taskRepository.findById(nullConfidence))
                .thenReturn(Optional.of(task(nullConfidence).status(TaskStatus.PENDING).build()));

        Task result = service.recomputePriorityScoreAndPublish(taskId, current, current, ready, ready);

        assertThat(result.getPriorityScore()).isEqualTo(PURE.compute(0.8, 1.0, 0.5 + 0.25 + 1.0, 0, 0));
    }

    @Test
    void recompute_dependentSum_addsAllDependentsRegardlessOfStatus_withNullConfidenceAsFull() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        Task current = task(taskId).score(0.8).confidence(1.0).build();
        storedTaskIs(current);
        when(dependencyRepository.findByTaskId(taskId)).thenReturn(List.of());
        when(dependencyRepository.findByDependsOnTaskId(taskId)).thenReturn(List.of(
                new TaskDependency(a, taskId, 0.4),
                new TaskDependency(b, taskId, null)));

        Task result = service.recomputePriorityScoreAndPublish(taskId, current, current, ready, ready);

        assertThat(result.getPriorityScore()).isEqualTo(PURE.compute(0.8, 1.0, 0, 0, 0.4 + 1.0));
        verify(taskRepository, never()).findById(a);
    }

    @Test
    void recomputeCompatOverload_readsTheViewSnapshotForBothSides() {
        Task current = task(taskId).score(0.8).confidence(1.0).build();
        storedTaskIs(current);
        noEdges();
        when(readinessRepository.findByIds(List.of(taskId)))
                .thenReturn(Map.of(taskId, new TaskReadinessSnapshot(taskId, 0, 3)));

        Task result = service.recomputePriorityScoreAndPublish(taskId, current, current);

        assertThat(result.getPriorityScore()).isEqualTo(PURE.compute(0.8, 1.0, 0, 3, 0));
    }

    @Test
    void recomputeCompatOverload_missingReadinessRow_throws() {
        Task current = task(taskId).build();
        when(readinessRepository.findByIds(List.of(taskId))).thenReturn(Map.of());

        assertThatThrownBy(() -> service.recomputePriorityScoreAndPublish(taskId, current, current))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(taskId.toString());
    }

    @Test
    void onScoreProposed_nullTaskId_throws() {
        assertThatThrownBy(() -> service.onScoreProposed(null, 0.5, 0.5))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("taskId");
    }

    @Test
    void onScoreProposed_mindScoreOutOfRange_throwsBeforeTouchingAnything() {
        assertThatThrownBy(() -> service.onScoreProposed(taskId, 1.01, 0.5))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("mindScore");
        assertThatThrownBy(() -> service.onScoreProposed(taskId, -0.1, 0.5))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("mindScore");

        verifyNoInteractions(taskRepository, readinessRepository, schedulingUpdateService);
    }

    @Test
    void onScoreProposed_confidenceOutOfRange_throwsBeforeTouchingAnything() {
        assertThatThrownBy(() -> service.onScoreProposed(taskId, 0.5, 2.0))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("confidence");

        verifyNoInteractions(taskRepository, readinessRepository, schedulingUpdateService);
    }

    @Test
    void onScoreProposed_boundaryValuesAndNullsAreAccepted() {
        storedTaskIs(task(taskId).build());
        when(readinessRepository.findByIds(List.of(taskId))).thenReturn(Map.of(taskId, ready));
        noEdges();

        service.onScoreProposed(taskId, 0.0, 1.0);
        service.onScoreProposed(taskId, 1.0, 0.0);
        service.onScoreProposed(taskId, null, null);
    }

    @Test
    void onScoreProposed_missingReadinessRow_throwsBeforeWriting() {
        when(readinessRepository.findByIds(List.of(taskId))).thenReturn(Map.of());

        assertThatThrownBy(() -> service.onScoreProposed(taskId, 0.5, 0.5))
                .isInstanceOf(IllegalStateException.class);
        verify(taskRepository, never()).update(any(), any());
    }

    @Test
    void onScoreProposed_unknownTask_throwsTaskNotFound() {
        when(readinessRepository.findByIds(List.of(taskId))).thenReturn(Map.of(taskId, ready));
        when(taskRepository.update(eq(taskId), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.onScoreProposed(taskId, 0.5, 0.5))
                .isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    void onScoreProposed_writesScoreAndConfidenceThenRequestsPublishOfTheNewPriority() {
        Task stored = task(taskId).build();
        when(readinessRepository.findByIds(List.of(taskId))).thenReturn(Map.of(taskId, ready));
        noEdges();
        Task[] persisted = {stored};
        when(taskRepository.update(eq(taskId), any())).thenAnswer(invocation -> {
            UnaryOperator<Task> mutator = invocation.getArgument(1);
            persisted[0] = mutator.apply(persisted[0]);
            return Optional.of(persisted[0]);
        });

        service.onScoreProposed(taskId, 0.8, 0.5);

        assertThat(persisted[0].getScore()).isEqualTo(0.8);
        assertThat(persisted[0].getConfidence()).isEqualTo(0.5);
        assertThat(persisted[0].getPriorityScore()).isEqualTo(PURE.compute(0.8, 0.5, 0, 0, 0));
        verify(schedulingUpdateService).requestPublishAfterCommit(taskId, listener);
    }
}
