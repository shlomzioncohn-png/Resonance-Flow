package com.rnd.inbox.bridge.task;

import com.rnd.inbox.messaging.kafka.TransientProcessingException;
import com.rnd.inbox.model.entity.Task;
import com.rnd.inbox.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.QueryTimeoutException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BridgeTaskPersistenceHandlerTest {

    @Mock
    private TaskService taskService;

    private BridgeTaskPersistenceHandler handler;

    @BeforeEach
    void setUp() {
        handler = new BridgeTaskPersistenceHandler(taskService);
    }

    @Test
    void aRaceLostToAConcurrentDuplicateIsResolvedAsANoOp() {
        Task incoming = new Task();
        Task alreadyPersisted = new Task();
        alreadyPersisted.setId(UUID.randomUUID());

        when(taskService.createTask(any(), any()))
                .thenThrow(new DataIntegrityViolationException("unique violation"));
        when(taskService.findExisting(incoming)).thenReturn(Optional.of(alreadyPersisted));

        assertThatCode(() -> handler.persist(incoming, UUID.randomUUID())).doesNotThrowAnyException();
    }

    @Test
    void aTechnicalFailureIsTranslatedIntoATransientProcessingException() {
        Task incoming = new Task();
        when(taskService.createTask(any(), any()))
                .thenThrow(new QueryTimeoutException("db timeout"));

        assertThatThrownBy(() -> handler.persist(incoming, UUID.randomUUID()))
                .isInstanceOf(TransientProcessingException.class);
    }
}
