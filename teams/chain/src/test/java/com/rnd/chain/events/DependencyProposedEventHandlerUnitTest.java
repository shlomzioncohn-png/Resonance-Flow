package com.rnd.chain.events;

import com.rnd.chain.dto.DependencyChangeSource;
import com.rnd.chain.exception.DuplicateDependencyException;
import com.rnd.chain.exception.InvalidDependencyException;
import com.rnd.chain.dto.TaskReadinessSnapshot;
import com.rnd.chain.repository.TaskReadinessRepository;
import com.rnd.chain.service.DependencyEdgeService;
import com.rnd.chain.service.SchedulingUpdateService;
import com.rnd.chain.service.TaskDependencyService;
import com.rnd.mind.event.Dependency;
import com.rnd.mind.event.DependencyProposedEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
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
    private final ProcessedEventStore store = mock(ProcessedEventStore.class);
    private final DependencyEdgeService edgeService = mock(DependencyEdgeService.class);
    private final SchedulingUpdateService schedulingUpdateService = mock(SchedulingUpdateService.class);
    private final SchedulingPublishListener publishListener = mock(SchedulingPublishListener.class);
    private final TaskReadinessRepository readinessRepository = mock(TaskReadinessRepository.class);
    private final DependencyProposedEventHandler handler = new DependencyProposedEventHandler(
            dependencyService, edgeService, schedulingUpdateService, publishListener, readinessRepository, new ChainEventProcessingTemplate(store));

    private final UUID eventId = UUID.randomUUID();
    private final UUID taskId = UUID.randomUUID();
    private final UUID tenantId = UUID.randomUUID();

    private void givenReadiness(int blockingCount) {
        when(readinessRepository.findByIds(List.of(taskId)))
                .thenReturn(Map.of(taskId, new TaskReadinessSnapshot(taskId, tenantId, blockingCount, 0)));
    }

    @Test
    void blockerDoneAndTaskNew_publishesAvailability() {
        when(store.tryClaim(any())).thenReturn(true);
        givenReadiness(0);

        handler.onMessage(event(UUID.randomUUID()));

        verify(edgeService).insertPlaceholderIfAbsent(taskId, tenantId);
        verify(schedulingUpdateService).requestPublishAfterCommit(taskId, publishListener);
    }

    @Test
    void emptyDependencyList_createsPlaceholderAndPublishes() {
        when(store.tryClaim(any())).thenReturn(true);
        givenReadiness(0);

        handler.onMessage(event());

        verify(edgeService).insertPlaceholderIfAbsent(taskId, tenantId);
        verify(schedulingUpdateService).requestPublishAfterCommit(taskId, publishListener);
    }

    @Test
    void existingReadyPlaceholder_publishes() {
        when(store.tryClaim(any())).thenReturn(true);
        givenReadiness(0);

        handler.onMessage(event(UUID.randomUUID()));

        verify(schedulingUpdateService).requestPublishAfterCommit(taskId, publishListener);
    }

    @Test
    void blockedTask_doesNotPublish() {
        when(store.tryClaim(any())).thenReturn(true);
        givenReadiness(1);

        handler.onMessage(event(UUID.randomUUID()));

        verify(schedulingUpdateService, never()).requestPublishAfterCommit(any(), any());
    }

    @Test
    void swallowedDuplicateEdge_stillPublishesWhenReady() {
        when(store.tryClaim(any())).thenReturn(true);
        doThrow(new DuplicateDependencyException("exists")).when(dependencyService)
                .addDependencyCreatingMissing(any(), any(), any(), anyString(), anyDouble(), any());
        givenReadiness(0);

        handler.onMessage(event(UUID.randomUUID()));

        verify(schedulingUpdateService).requestPublishAfterCommit(taskId, publishListener);
    }

    @Test
    void publishesOnlyForTheTask_notForItsDependencies() {
        UUID target = UUID.randomUUID();
        when(store.tryClaim(any())).thenReturn(true);
        givenReadiness(0);

        handler.onMessage(event(target));

        verify(schedulingUpdateService, never()).requestPublishAfterCommit(eq(target), any());
    }

    private DependencyProposedEvent event(UUID... dependsOn) {
        DependencyProposedEvent.Builder builder = DependencyProposedEvent.newBuilder()
                .setEventId(eventId.toString())
                .setTaskId(taskId.toString())
                .setTenantId(tenantId.toString());
        for (UUID id : dependsOn) {
            builder.addDependencies(
                    Dependency.newBuilder().setDependsOnTaskId(id.toString()).setConfidence(0.7f).build());
        }
        return builder.build();
    }

    @Test
    void eachTarget_isAddedAsAMindEdgeWithItsConfidenceTenantAndASourceRef() {
        UUID target = UUID.randomUUID();
        when(store.tryClaim(any())).thenReturn(true);

        handler.onMessage(event(target));

        verify(dependencyService).addDependencyCreatingMissing(taskId, target, DependencyChangeSource.MIND,
                eventId + ":" + taskId + ":" + target, (double) 0.7f, tenantId);
    }

    @Test
    void duplicateTargetInOneEvent_isAppliedOnce() {
        UUID target = UUID.randomUUID();
        when(store.tryClaim(any())).thenReturn(true);

        handler.onMessage(event(target, target));

        verify(dependencyService, times(1))
                .addDependencyCreatingMissing(any(), any(), any(), anyString(), anyDouble(), any());
    }

    @Test
    void alreadyClaimedEvent_isSkipped() {
        when(store.tryClaim(any())).thenReturn(false);

        handler.onMessage(event(UUID.randomUUID()));

        verify(schedulingUpdateService, never()).requestPublishAfterCommit(any(), any());

        verify(dependencyService, never())
                .addDependencyCreatingMissing(any(), any(), any(), anyString(), anyDouble(), any());
    }

    @Test
    void duplicateEdge_isBenign() {
        when(store.tryClaim(any())).thenReturn(true);
        doThrow(new DuplicateDependencyException("exists")).when(dependencyService)
                .addDependencyCreatingMissing(any(), any(), any(), anyString(), anyDouble(), any());

        handler.onMessage(event(UUID.randomUUID()));
    }

    @Test
    void invalidEdge_isNonRetryableAndReleasesTheClaim() {
        when(store.tryClaim(any())).thenReturn(true);
        doThrow(new InvalidDependencyException("cycle")).when(dependencyService)
                .addDependencyCreatingMissing(any(), any(), any(), anyString(), anyDouble(), any());

        assertThrows(NonRetryableEventException.class, () -> handler.onMessage(event(UUID.randomUUID())));
        verify(store).release(eventId);
    }

    @Test
    void malformedTaskId_isNonRetryable() {
        DependencyProposedEvent bad = DependencyProposedEvent.newBuilder()
                .setEventId(eventId.toString()).setTaskId("not-a-uuid").setTenantId(tenantId.toString()).build();

        assertThrows(NonRetryableEventException.class, () -> handler.onMessage(bad));
    }

    @Test
    void missingTenantId_isNonRetryable() {
        DependencyProposedEvent noTenant = DependencyProposedEvent.newBuilder()
                .setEventId(eventId.toString()).setTaskId(taskId.toString()).build();

        assertThrows(NonRetryableEventException.class, () -> handler.onMessage(noTenant));
    }
}
