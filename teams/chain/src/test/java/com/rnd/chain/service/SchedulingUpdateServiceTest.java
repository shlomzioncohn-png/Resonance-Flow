package com.rnd.chain.service;

import com.rnd.chain.events.SchedulingPublishListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/** Unit tests for {@code requestPublishAfterCommit}; the transaction is simulated by hand. */
class SchedulingUpdateServiceTest {

    private final SchedulingUpdateService service = new SchedulingUpdateService(event -> { });
    private final SchedulingPublishListener listener = mock(SchedulingPublishListener.class);

    @BeforeEach
    void startTransaction() {
        TransactionSynchronizationManager.initSynchronization();
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

        verify(listener).onCommit(taskId);
        verifyNoMoreInteractions(listener);
    }

    @Test
    void sameTaskRequestedTwice_publishesOnce() {
        UUID taskId = UUID.randomUUID();

        service.requestPublishAfterCommit(taskId, listener);
        service.requestPublishAfterCommit(taskId, listener);
        commit();

        verify(listener).onCommit(taskId);
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

        verify(listener).onCommit(a);
        verify(listener).onCommit(b);
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

        verify(listener).onCommit(taskId);
    }
}
