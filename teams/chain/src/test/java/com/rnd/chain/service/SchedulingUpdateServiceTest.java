package com.rnd.chain.service;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskReadinessSnapshot;
import com.rnd.chain.events.SchedulingPublishListener;
import com.rnd.chain.events.TaskSchedulingUpdatedEvent;
import com.rnd.chain.repository.TaskReadinessRepository;
import com.rnd.chain.dto.TaskTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/** Unit tests for {@code requestPublishAfterCommit}; the transaction is simulated by hand. */
class SchedulingUpdateServiceTest {

    private final List<TaskSchedulingUpdatedEvent> published = new ArrayList<>();
    private final TaskReadinessRepository readinessRepository = mock(TaskReadinessRepository.class);
    private final SchedulingUpdateService service = new SchedulingUpdateService(published::add, readinessRepository);
    private final SchedulingPublishListener listener = mock(SchedulingPublishListener.class);

    /** Makes the view report {@code taskId} as having the given blocker counts. */
    private void viewReports(UUID taskId, int blockingCount, int openConstraintCount) {
        when(readinessRepository.findByIds(List.of(taskId)))
                .thenReturn(Map.of(taskId, new TaskReadinessSnapshot(taskId, TaskTestFixtures.DEFAULT_TENANT_ID, blockingCount, openConstraintCount)));
    }

    @BeforeEach
    void startTransaction() {
        TransactionSynchronizationManager.initSynchronization();
        // Default: every task the view is asked about is ready.
        when(readinessRepository.findByIds(anyList())).thenAnswer(invocation -> {
            List<UUID> ids = invocation.getArgument(0);
            return Map.of(ids.get(0), new TaskReadinessSnapshot(ids.get(0), TaskTestFixtures.DEFAULT_TENANT_ID, 0, 0));
        });
    }

    @AfterEach
    void cleanUp() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            // afterCompletion unbinds the thread's pending set, which clearSynchronization alone would leak.
            rollback();
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private void commit() {
        List<TransactionSynchronization> synchronizations =
                new ArrayList<>(TransactionSynchronizationManager.getSynchronizations());
        synchronizations.forEach(TransactionSynchronization::afterCommit);
        synchronizations.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
    }

    private void rollback() {
        List<TransactionSynchronization> synchronizations =
                new ArrayList<>(TransactionSynchronizationManager.getSynchronizations());
        synchronizations.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
    }

    @Test
    void noActiveTransaction_dropsThePublishWithoutFailing() {
        TransactionSynchronizationManager.clearSynchronization();

        service.requestPublishAfterCommit(UUID.randomUUID(), listener);

        verifyNoInteractions(listener);
    }

    @Test
    void nothingIsPublishedBeforeCommit() {
        service.requestPublishAfterCommit(UUID.randomUUID(), listener);

        verifyNoInteractions(listener);
    }

    @Test
    void commit_publishesTheQueuedTask() {
        UUID taskId = UUID.randomUUID();

        service.requestPublishAfterCommit(taskId, listener);
        commit();

        verify(listener).onCommit(taskId, true);
        verifyNoMoreInteractions(listener);
    }

    @Test
    void sameTaskRequestedTwice_publishesOnce() {
        UUID taskId = UUID.randomUUID();

        service.requestPublishAfterCommit(taskId, listener);
        service.requestPublishAfterCommit(taskId, listener);
        commit();

        verify(listener).onCommit(taskId, true);
        verifyNoMoreInteractions(listener);
    }

    @Test
    void differentTasks_registerOneSynchronizationAndPublishEachOnce() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        service.requestPublishAfterCommit(a, listener);
        service.requestPublishAfterCommit(b, listener);

        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
        commit();

        verify(listener).onCommit(a, true);
        verify(listener).onCommit(b, true);
        verifyNoMoreInteractions(listener);
    }

    @Test
    void rollback_neverPublishesAndForgetsThePendingSet() {
        UUID taskId = UUID.randomUUID();

        service.requestPublishAfterCommit(taskId, listener);
        rollback();

        verifyNoInteractions(listener);

        // A fresh transaction must not be deduplicated against the rolled-back one.
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.initSynchronization();
        service.requestPublishAfterCommit(taskId, listener);
        commit();

        verify(listener).onCommit(taskId, true);
    }

    @Test
    void commit_passesTheViewReadinessCapturedAtRequestTime() {
        UUID ready = UUID.randomUUID();
        UUID blocked = UUID.randomUUID();
        viewReports(ready, 0, 0);
        viewReports(blocked, 1, 0);

        service.requestPublishAfterCommit(ready, listener);
        service.requestPublishAfterCommit(blocked, listener);
        commit();

        verify(listener).onCommit(ready, true);
        verify(listener).onCommit(blocked, false);
        verifyNoMoreInteractions(listener);
    }

    @Test
    void openConstraintAlone_makesTheTaskUnavailable() {
        UUID taskId = UUID.randomUUID();
        viewReports(taskId, 0, 2);

        service.requestPublishAfterCommit(taskId, listener);
        commit();

        verify(listener).onCommit(taskId, false);
    }

    @Test
    void sameTaskRequestedTwice_publishesTheLatestViewValue() {
        UUID taskId = UUID.randomUUID();
        viewReports(taskId, 1, 0);
        service.requestPublishAfterCommit(taskId, listener);
        viewReports(taskId, 0, 0);
        service.requestPublishAfterCommit(taskId, listener);

        assertThat(TransactionSynchronizationManager.getSynchronizations()).hasSize(1);
        commit();

        verify(listener).onCommit(taskId, true);
        verifyNoMoreInteractions(listener);
    }

    @Test
    void missingViewRow_throwsAndQueuesNothing() {
        UUID taskId = UUID.randomUUID();
        when(readinessRepository.findByIds(List.of(taskId))).thenReturn(Map.of());

        assertThatThrownBy(() -> service.requestPublishAfterCommit(taskId, listener))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No task_readiness row found for task " + taskId);

        assertThat(TransactionSynchronizationManager.getSynchronizations()).isEmpty();
        commit();
        verifyNoInteractions(listener);
    }

    @Test
    void publishSchedulingUpdate_usesTheSuppliedAvailabilityNotTheStoredTask() {
        Task task = TaskTestFixtures.defaultTask(UUID.randomUUID()).priorityScore(6.5).build();

        service.publishSchedulingUpdate(task, false);

        assertThat(published).singleElement().satisfies(event -> {
            assertThat(event.getTaskId()).isEqualTo(task.getId());
            assertThat(event.isAvailable()).isFalse();
            assertThat(event.getPriorityScore()).isEqualTo(6.5);
        });
    }
}
