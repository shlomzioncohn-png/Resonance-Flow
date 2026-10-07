package com.rnd.chain.events;

import com.rnd.chain.dto.DependencyChangeSource;
import com.rnd.chain.dto.MindDependencyPayload;
import com.rnd.chain.dto.MindProposedDependency;
import com.rnd.chain.dto.Task;
import com.rnd.chain.exception.DuplicateDependencyException;
import com.rnd.chain.exception.TaskNotFoundException;
import com.rnd.chain.repository.TaskRepository;
import com.rnd.chain.service.TaskDependencyService;
import com.rnd.chain.service.UnresolvedDependencyService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MindDependencyEventHandlerTest {

    @Mock
    private TaskDependencyService taskDependencyService;

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private UnresolvedDependencyService unresolvedDependencyService;

    private MindDependencyEventHandler handler;

    @BeforeEach
    void setUp() {
        handler =
                new MindDependencyEventHandler(
                        taskDependencyService,
                        taskRepository,
                        unresolvedDependencyService);
    }

    @Test
    void getSupportedEventType_shouldReturnMindDependencyEventType() {

        assertEquals(
                "task.dependencies.proposed",
                handler.getSupportedEventType());
    }

    @Test
    void getPayloadType_shouldReturnMindDependencyPayloadClass() {

        assertEquals(
                MindDependencyPayload.class,
                handler.getPayloadType());
    }

    @Test
    void handle_shouldAddAllDependenciesWithUniqueSourceRefs() {

        UUID taskId = UUID.randomUUID();
        UUID firstDependencyId = UUID.randomUUID();
        UUID secondDependencyId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        MindDependencyPayload payload =
                new MindDependencyPayload(
                        taskId,
                        List.of(
                                new MindProposedDependency(
                                        firstDependencyId,
                                        0.9),
                                new MindProposedDependency(
                                        secondDependencyId,
                                        0.8)));

        EventEnvelope<MindDependencyPayload> event =
                createEvent(eventId, payload);

        handler.handle(event);

        verify(taskDependencyService)
                .addDependency(
                        taskId,
                        firstDependencyId,
                        DependencyChangeSource.MIND,
                        eventId
                                + ":"
                                + taskId
                                + ":"
                                + firstDependencyId);

        verify(taskDependencyService)
                .addDependency(
                        taskId,
                        secondDependencyId,
                        DependencyChangeSource.MIND,
                        eventId
                                + ":"
                                + taskId
                                + ":"
                                + secondDependencyId);

        verify(unresolvedDependencyService, never())
                .recordMissingTarget(
                        any(),
                        any(),
                        any(),
                        anyString());
    }

    @Test
    void handle_whenSameDependencyAppearsTwice_shouldProcessItOnlyOnce() {

        UUID taskId = UUID.randomUUID();
        UUID dependencyId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        MindDependencyPayload payload =
                new MindDependencyPayload(
                        taskId,
                        List.of(
                                new MindProposedDependency(
                                        dependencyId,
                                        0.9),
                                new MindProposedDependency(
                                        dependencyId,
                                        0.7)));

        EventEnvelope<MindDependencyPayload> event =
                createEvent(eventId, payload);

        handler.handle(event);

        String expectedSourceRef =
                eventId
                        + ":"
                        + taskId
                        + ":"
                        + dependencyId;

        verify(taskDependencyService)
                .addDependency(
                        taskId,
                        dependencyId,
                        DependencyChangeSource.MIND,
                        expectedSourceRef);

        verify(unresolvedDependencyService, never())
                .recordMissingTarget(
                        any(),
                        any(),
                        any(),
                        anyString());
    }

    @Test
    void handle_whenDependencyAlreadyExists_shouldIgnoreDuplicate() {

        UUID taskId = UUID.randomUUID();
        UUID dependencyId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        MindDependencyPayload payload =
                new MindDependencyPayload(
                        taskId,
                        List.of(
                                new MindProposedDependency(
                                        dependencyId,
                                        0.9)));

        EventEnvelope<MindDependencyPayload> event =
                createEvent(eventId, payload);

        String sourceRef =
                eventId
                        + ":"
                        + taskId
                        + ":"
                        + dependencyId;

        when(taskDependencyService.addDependency(
                taskId,
                dependencyId,
                DependencyChangeSource.MIND,
                sourceRef))
                .thenThrow(
                        new DuplicateDependencyException(
                                "Dependency already exists"));

        handler.handle(event);

        verify(taskDependencyService)
                .addDependency(
                        taskId,
                        dependencyId,
                        DependencyChangeSource.MIND,
                        sourceRef);

        verify(unresolvedDependencyService, never())
                .recordMissingTarget(
                        any(),
                        any(),
                        any(),
                        anyString());
    }

    @Test
    void handle_whenDependencyTargetMissing_shouldStoreUnresolvedAndContinue() {

        UUID taskId = UUID.randomUUID();

        UUID existingDependencyId =
                UUID.randomUUID();

        UUID missingDependencyId =
                UUID.randomUUID();

        UUID eventId =
                UUID.randomUUID();

        MindDependencyPayload payload =
                new MindDependencyPayload(
                        taskId,
                        List.of(
                                new MindProposedDependency(
                                        missingDependencyId,
                                        0.8),
                                new MindProposedDependency(
                                        existingDependencyId,
                                        0.9)));

        EventEnvelope<MindDependencyPayload> event =
                createEvent(eventId, payload);

        String missingSourceRef =
                eventId
                        + ":"
                        + taskId
                        + ":"
                        + missingDependencyId;

        String existingSourceRef =
                eventId
                        + ":"
                        + taskId
                        + ":"
                        + existingDependencyId;

        when(taskDependencyService.addDependency(
                taskId,
                missingDependencyId,
                DependencyChangeSource.MIND,
                missingSourceRef))
                .thenThrow(
                        new TaskNotFoundException(
                                missingDependencyId));

        when(taskRepository.findById(taskId))
                .thenReturn(
                        Optional.of(
                                mock(Task.class)));

        when(taskRepository.findById(missingDependencyId))
                .thenReturn(Optional.empty());

        handler.handle(event);

        verify(unresolvedDependencyService)
                .recordMissingTarget(
                        taskId,
                        missingDependencyId,
                        DependencyChangeSource.MIND,
                        missingSourceRef);

        /*
         * Important:
         * failure of C must not prevent the next dependency
         * from being processed.
         */
        verify(taskDependencyService)
                .addDependency(
                        taskId,
                        existingDependencyId,
                        DependencyChangeSource.MIND,
                        existingSourceRef);
    }

    @Test
    void handle_whenSourceTaskMissing_shouldPropagateTaskNotFoundException() {

        UUID taskId = UUID.randomUUID();
        UUID dependencyId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        MindDependencyPayload payload =
                new MindDependencyPayload(
                        taskId,
                        List.of(
                                new MindProposedDependency(
                                        dependencyId,
                                        0.9)));

        EventEnvelope<MindDependencyPayload> event =
                createEvent(eventId, payload);

        String sourceRef =
                eventId
                        + ":"
                        + taskId
                        + ":"
                        + dependencyId;

        when(taskDependencyService.addDependency(
                taskId,
                dependencyId,
                DependencyChangeSource.MIND,
                sourceRef))
                .thenThrow(
                        new TaskNotFoundException(
                                taskId));

        when(taskRepository.findById(taskId))
                .thenReturn(Optional.empty());

        when(taskRepository.findById(dependencyId))
                .thenReturn(
                        Optional.of(
                                mock(Task.class)));

        assertThrows(
                TaskNotFoundException.class,
                () -> handler.handle(event));

        verify(unresolvedDependencyService, never())
                .recordMissingTarget(
                        any(),
                        any(),
                        any(),
                        anyString());
    }

    @Test
    void handle_whenTechnicalFailureOccurs_shouldPropagateException() {

        UUID taskId = UUID.randomUUID();
        UUID dependencyId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        MindDependencyPayload payload =
                new MindDependencyPayload(
                        taskId,
                        List.of(
                                new MindProposedDependency(
                                        dependencyId,
                                        0.9)));

        EventEnvelope<MindDependencyPayload> event =
                createEvent(eventId, payload);

        String sourceRef =
                eventId
                        + ":"
                        + taskId
                        + ":"
                        + dependencyId;

        RuntimeException technicalFailure =
                new RuntimeException(
                        "DB failure");

        when(taskDependencyService.addDependency(
                taskId,
                dependencyId,
                DependencyChangeSource.MIND,
                sourceRef))
                .thenThrow(technicalFailure);

        RuntimeException thrown =
                assertThrows(
                        RuntimeException.class,
                        () -> handler.handle(event));

        assertEquals(
                "DB failure",
                thrown.getMessage());

        verify(unresolvedDependencyService, never())
                .recordMissingTarget(
                        any(),
                        any(),
                        any(),
                        anyString());
    }

    @Test
    void handle_whenTaskIdMissing_shouldThrowNonRetryableEventException() {

        MindDependencyPayload payload =
                new MindDependencyPayload(
                        null,
                        List.of());

        EventEnvelope<MindDependencyPayload> event =
                createEvent(
                        UUID.randomUUID(),
                        payload);

        assertThrows(
                NonRetryableEventException.class,
                () -> handler.handle(event));

        verify(taskDependencyService, never())
                .addDependency(
                        any(),
                        any(),
                        any(),
                        any());
    }

    @Test
    void handle_whenDependenciesMissing_shouldThrowNonRetryableEventException() {

        MindDependencyPayload payload =
                new MindDependencyPayload(
                        UUID.randomUUID(),
                        null);

        EventEnvelope<MindDependencyPayload> event =
                createEvent(
                        UUID.randomUUID(),
                        payload);

        assertThrows(
                NonRetryableEventException.class,
                () -> handler.handle(event));

        verify(taskDependencyService, never())
                .addDependency(
                        any(),
                        any(),
                        any(),
                        any());
    }

    @Test
    void handle_whenDependencyIsNull_shouldThrowNonRetryableEventException() {

        MindDependencyPayload payload =
                new MindDependencyPayload(
                        UUID.randomUUID(),
                        java.util.Arrays.asList(
                                (MindProposedDependency) null));

        EventEnvelope<MindDependencyPayload> event =
                createEvent(
                        UUID.randomUUID(),
                        payload);

        assertThrows(
                NonRetryableEventException.class,
                () -> handler.handle(event));

        verify(taskDependencyService, never())
                .addDependency(
                        any(),
                        any(),
                        any(),
                        any());
    }

    @Test
    void handle_whenDependsOnTaskIdMissing_shouldThrowNonRetryableEventException() {

        MindDependencyPayload payload =
                new MindDependencyPayload(
                        UUID.randomUUID(),
                        List.of(
                                new MindProposedDependency(
                                        null,
                                        0.9)));

        EventEnvelope<MindDependencyPayload> event =
                createEvent(
                        UUID.randomUUID(),
                        payload);

        assertThrows(
                NonRetryableEventException.class,
                () -> handler.handle(event));

        verify(taskDependencyService, never())
                .addDependency(
                        any(),
                        any(),
                        any(),
                        any());
    }

    @Test
    void handle_whenConfidenceMissing_shouldThrowNonRetryableEventException() {

        MindDependencyPayload payload =
                new MindDependencyPayload(
                        UUID.randomUUID(),
                        List.of(
                                new MindProposedDependency(
                                        UUID.randomUUID(),
                                        null)));

        EventEnvelope<MindDependencyPayload> event =
                createEvent(
                        UUID.randomUUID(),
                        payload);

        assertThrows(
                NonRetryableEventException.class,
                () -> handler.handle(event));

        verify(taskDependencyService, never())
                .addDependency(
                        any(),
                        any(),
                        any(),
                        any());
    }

    @Test
    void handle_whenConfidenceBelowZero_shouldThrowNonRetryableEventException() {

        MindDependencyPayload payload =
                new MindDependencyPayload(
                        UUID.randomUUID(),
                        List.of(
                                new MindProposedDependency(
                                        UUID.randomUUID(),
                                        -0.1)));

        EventEnvelope<MindDependencyPayload> event =
                createEvent(
                        UUID.randomUUID(),
                        payload);

        assertThrows(
                NonRetryableEventException.class,
                () -> handler.handle(event));

        verify(taskDependencyService, never())
                .addDependency(
                        any(),
                        any(),
                        any(),
                        any());
    }

    @Test
    void handle_whenConfidenceAboveOne_shouldThrowNonRetryableEventException() {

        MindDependencyPayload payload =
                new MindDependencyPayload(
                        UUID.randomUUID(),
                        List.of(
                                new MindProposedDependency(
                                        UUID.randomUUID(),
                                        1.1)));

        EventEnvelope<MindDependencyPayload> event =
                createEvent(
                        UUID.randomUUID(),
                        payload);

        assertThrows(
                NonRetryableEventException.class,
                () -> handler.handle(event));

        verify(taskDependencyService, never())
                .addDependency(
                        any(),
                        any(),
                        any(),
                        any());
    }

    @Test
    void handle_whenDependenciesEmpty_shouldCompleteWithoutAddingAnything() {

        MindDependencyPayload payload =
                new MindDependencyPayload(
                        UUID.randomUUID(),
                        List.of());

        EventEnvelope<MindDependencyPayload> event =
                createEvent(
                        UUID.randomUUID(),
                        payload);

        handler.handle(event);

        verify(taskDependencyService, never())
                .addDependency(
                        any(),
                        any(),
                        any(),
                        any());

        verify(unresolvedDependencyService, never())
                .recordMissingTarget(
                        any(),
                        any(),
                        any(),
                        anyString());
    }

    private EventEnvelope<MindDependencyPayload> createEvent(
            UUID eventId,
            MindDependencyPayload payload) {

        return new EventEnvelope<>(
                eventId,
                "task.dependencies.proposed",
                Instant.now(),
                "test-correlation-id",
                payload);
    }
}