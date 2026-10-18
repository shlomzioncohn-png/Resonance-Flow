package com.rnd.chain.events;

import com.rnd.chain.dto.DependencyChangeSource;
import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.exception.DuplicateDependencyException;
import com.rnd.chain.exception.InvalidDependencyException;
import com.rnd.chain.repository.TaskRepository;
import com.rnd.chain.service.TaskDependencyService;
import com.rnd.chain.service.UnresolvedDependencyService;
import com.rnd.mind.event.Dependency;
import com.rnd.mind.event.DependencyProposedEvent;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Pure unit test: collaborators are mocks, so it needs no database and does not depend on the schema. */
class DependencyProposedEventHandlerUnitTest {

    private final TaskDependencyService dependencyService = mock(TaskDependencyService.class);
    private final TaskRepository taskRepository = mock(TaskRepository.class);
    private final UnresolvedDependencyService unresolved = mock(UnresolvedDependencyService.class);
    private final ProcessedEventStore store = mock(ProcessedEventStore.class);
    private final DependencyProposedEventHandler handler = new DependencyProposedEventHandler(
            dependencyService, taskRepository, unresolved, new ChainEventProcessingTemplate(store));

    private final UUID eventId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();

    private DependencyProposedEvent event(UUID... dependsOn) {
        DependencyProposedEvent.Builder builder = DependencyProposedEvent.newBuilder()
                .setEventId(eventId.toString())
                .setTaskId(taskId.toString());
        for (UUID id : dependsOn) {
            builder.addDependencies(
                    Dependency.newBuilder().setDependsOnTaskId(id.toString()).setConfidence(0.7f).build());
        }
        return builder.build();
    }

    private void taskExists(UUID id) {
        when(taskRepository.findById(id)).thenReturn(Optional.of(Task.builder().id(id).status(TaskStatus.PENDING).build()));
    }

    @Test
    void existingTargets_areAddedAsMindEdgesWithTheirConfidenceAndASourceRef() {
        UUID target = UUID.randomUUID();
        when(store.tryClaim(any())).thenReturn(true);
        taskExists(taskId);
        taskExists(target);

        handler.onMessage(event(target));

        verify(dependencyService).addDependency(taskId, target, DependencyChangeSource.MIND,
                eventId + ":" + taskId + ":" + target, (double) 0.7f);
    }

    @Test
    void duplicateTargetInOneEvent_isAppliedOnce() {
        UUID target = UUID.randomUUID();
        when(store.tryClaim(any())).thenReturn(true);
        taskExists(taskId);
        taskExists(target);

        handler.onMessage(event(target, target));

        verify(dependencyService, times(1)).addDependency(any(), any(), any(), anyString(), anyDouble());
    }

    @Test
    void missingTarget_isRecordedAsUnresolvedInsteadOfRetried() {
        UUID target = UUID.randomUUID();
        when(store.tryClaim(any())).thenReturn(true);
        taskExists(taskId);
        when(taskRepository.findById(target)).thenReturn(Optional.empty());

        handler.onMessage(event(target));

        verify(unresolved).recordMissingTarget(eq(taskId), eq(target), eq(DependencyChangeSource.MIND), anyString());
        verify(dependencyService, never()).addDependency(any(), any(), any(), anyString(), anyDouble());
    }

    @Test
    void missingSourceTask_isRetryable() {
        when(store.tryClaim(any())).thenReturn(true);
        when(taskRepository.findById(taskId)).thenReturn(Optional.empty());

        assertThrows(ReferencedTaskNotFoundException.class, () -> handler.onMessage(event(UUID.randomUUID())));
    }

    @Test
    void alreadyClaimedEvent_isSkipped() {
        when(store.tryClaim(any())).thenReturn(false);

        handler.onMessage(event(UUID.randomUUID()));

        verify(dependencyService, never()).addDependency(any(), any(), any(), anyString(), anyDouble());
    }

    @Test
    void duplicateEdge_isBenign() {
        UUID target = UUID.randomUUID();
        when(store.tryClaim(any())).thenReturn(true);
        taskExists(taskId);
        taskExists(target);
        doThrow(new DuplicateDependencyException("exists")).when(dependencyService)
                .addDependency(any(), any(), any(), anyString(), anyDouble());

        handler.onMessage(event(target));
    }

    @Test
    void invalidEdge_isNonRetryableAndReleasesTheClaim() {
        UUID target = UUID.randomUUID();
        when(store.tryClaim(any())).thenReturn(true);
        taskExists(taskId);
        taskExists(target);
        doThrow(new InvalidDependencyException("cycle")).when(dependencyService)
                .addDependency(any(), any(), any(), anyString(), anyDouble());

        assertThrows(NonRetryableEventException.class, () -> handler.onMessage(event(target)));
        verify(store).release(eventId);
    }

    @Test
    void malformedTaskId_isNonRetryable() {
        DependencyProposedEvent bad = DependencyProposedEvent.newBuilder()
                .setEventId(eventId.toString()).setTaskId("not-a-uuid").build();

        assertThrows(NonRetryableEventException.class, () -> handler.onMessage(bad));
    }
}
