package com.rnd.inbox.chain.scheduling;

import com.rnd.inbox.exception.TaskNotFoundException;
import com.rnd.inbox.messaging.kafka.TransientProcessingException;
import com.rnd.inbox.service.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChainSchedulingPersistenceHandlerTest {

    @Mock
    private TaskService taskService;

    private ChainSchedulingPersistenceHandler handler;

    @BeforeEach
    void setUp() {
        handler = new ChainSchedulingPersistenceHandler(taskService);
    }

    private ChainSchedulingEventDto eventFor(UUID taskId) {
        ChainSchedulingEventDto dto = new ChainSchedulingEventDto();
        dto.setEventId(UUID.randomUUID().toString());
        dto.setTaskId(taskId.toString());
        dto.setAvailable(true);
        return dto;
    }

    @Test
    void passesThePriorityScoreThroughAsAFloat() {
        UUID taskId = UUID.randomUUID();
        ChainSchedulingEventDto dto = eventFor(taskId);
        dto.setPriorityScore(7.5);

        handler.apply(dto);

        verify(taskService).applySchedulingUpdate(eq(taskId), eq(true), eq(7.5f), any());
    }

    @Test
    void passesNullWhenNoPriorityScoreWasSentSoTheStoredValueSurvives() {
        UUID taskId = UUID.randomUUID();

        handler.apply(eventFor(taskId));

        verify(taskService).applySchedulingUpdate(eq(taskId), eq(true), isNull(), any());
    }

    @Test
    void aTechnicalFailureIsTranslatedIntoATransientProcessingException() {
        when(taskService.applySchedulingUpdate(any(), anyBoolean(), any(), any()))
                .thenThrow(new QueryTimeoutException("db timeout"));

        assertThatThrownBy(() -> handler.apply(eventFor(UUID.randomUUID())))
                .isInstanceOf(TransientProcessingException.class);
    }

    @Test
    void aMissingTaskIsPermanentAndIsNotDisguisedAsTransient() {
        UUID taskId = UUID.randomUUID();
        when(taskService.applySchedulingUpdate(any(), anyBoolean(), any(), any()))
                .thenThrow(new TaskNotFoundException(taskId));

        assertThatThrownBy(() -> handler.apply(eventFor(taskId)))
                .isInstanceOf(TaskNotFoundException.class);
    }
}
