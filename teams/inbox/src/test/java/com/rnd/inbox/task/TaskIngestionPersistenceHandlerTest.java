package com.rnd.inbox.task;

import com.rnd.inbox.shared.messaging.kafka.TransientProcessingException;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskIngestionPersistenceHandlerTest {

    @Mock
    private TaskService taskService;

    private TaskIngestionPersistenceHandler handler;

    @BeforeEach
    void setUp() {
        handler = new TaskIngestionPersistenceHandler(taskService);
    }

    @Test
    void aRaceLostToAConcurrentDuplicateIsResolvedAsANoOp() {
        Task incoming = new Task();
        Task alreadyPersisted = new Task();
        alreadyPersisted.setId(UUID.randomUUID());

        when(taskService.createTask(any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("unique violation"));
        when(taskService.findExisting(incoming)).thenReturn(Optional.of(alreadyPersisted));

        assertThatCode(() -> handler.persist(incoming, null, UUID.randomUUID())).doesNotThrowAnyException();
    }

    @Test
    void aTechnicalFailureIsTranslatedIntoATransientProcessingException() {
        Task incoming = new Task();
        when(taskService.createTask(any(), any(), any()))
                .thenThrow(new QueryTimeoutException("db timeout"));

        assertThatThrownBy(() -> handler.persist(incoming, null, UUID.randomUUID()))
                .isInstanceOf(TransientProcessingException.class);
    }

    @Test
    void anAssignmentIsPassedThroughToTaskService() {
        Task incoming = new Task();
        Assignment assignment = new Assignment();
        when(taskService.createTask(any(), any(), any())).thenReturn(incoming);

        handler.persist(incoming, assignment, UUID.randomUUID());

        verify(taskService).createTask(eq(incoming), eq(assignment), any());
    }
}
