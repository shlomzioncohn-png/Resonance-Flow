package com.rnd.chain.service;

import com.rnd.chain.dto.DependencyChangeSource;
import com.rnd.chain.dto.UnresolvedDependency;
import com.rnd.chain.exception.DuplicateDependencyException;
import com.rnd.chain.repository.UnresolvedDependencyRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UnresolvedDependencyServiceTest {

    @Mock
    private UnresolvedDependencyRepository repository;

    @Mock
    private TaskDependencyService taskDependencyService;

    private UnresolvedDependencyService service;

    private final Instant now =
            Instant.parse("2026-10-05T12:00:00Z");

    private final Clock clock =
            Clock.fixed(
                    now,
                    ZoneOffset.UTC);

    @BeforeEach
    void setUp() {

        service =
                new UnresolvedDependencyService(
                        repository,
                        taskDependencyService,
                        clock);
    }

    @Test
    void recordMissingTarget_whenNotExists_shouldSaveNewUnresolvedDependency() {

        UUID taskId = UUID.randomUUID();
        UUID dependsOnTaskId = UUID.randomUUID();

        when(repository.findByTaskIdAndDependsOnTaskId(
                taskId,
                dependsOnTaskId))
                .thenReturn(Optional.empty());

        when(repository.save(any(UnresolvedDependency.class)))
                .thenAnswer(invocation ->
                        invocation.getArgument(0));

        UnresolvedDependency result =
                service.recordMissingTarget(
                        taskId,
                        dependsOnTaskId,
                        DependencyChangeSource.MIND,
                        "source-ref");

        assertEquals(
                taskId,
                result.taskId());

        assertEquals(
                dependsOnTaskId,
                result.dependsOnTaskId());

        assertEquals(
                DependencyChangeSource.MIND,
                result.source());

        assertEquals(
                "source-ref",
                result.sourceRef());

        assertEquals(
                UnresolvedDependencyService.TARGET_TASK_NOT_FOUND,
                result.reason());

        assertEquals(
                now,
                result.createdAt());

        verify(repository)
                .save(any(UnresolvedDependency.class));
    }

    @Test
    void recordMissingTarget_whenAlreadyExists_shouldReturnExistingWithoutSavingAgain() {

        UUID taskId = UUID.randomUUID();
        UUID dependsOnTaskId = UUID.randomUUID();

        UnresolvedDependency existing =
                createUnresolved(
                        taskId,
                        dependsOnTaskId);

        when(repository.findByTaskIdAndDependsOnTaskId(
                taskId,
                dependsOnTaskId))
                .thenReturn(Optional.of(existing));

        UnresolvedDependency result =
                service.recordMissingTarget(
                        taskId,
                        dependsOnTaskId,
                        DependencyChangeSource.MIND,
                        existing.sourceRef());

        assertEquals(
                existing,
                result);

        verify(repository, never())
                .save(any());
    }

    @Test
    void resolveWaitingForTask_whenNoDependenciesWaiting_shouldDoNothing() {

        UUID taskId = UUID.randomUUID();

        when(repository.findByDependsOnTaskId(taskId))
                .thenReturn(List.of());

        service.resolveWaitingForTask(taskId);

        verify(taskDependencyService, never())
                .addDependency(
                        any(),
                        any(),
                        any(),
                        any());

        verify(repository, never())
                .deleteById(any());
    }

    @Test
    void resolveWaitingForTask_whenDependencyCanBeResolved_shouldAddAndDeleteUnresolved() {

        UUID taskId = UUID.randomUUID();
        UUID newlyAvailableTaskId = UUID.randomUUID();

        UnresolvedDependency unresolved =
                createUnresolved(
                        taskId,
                        newlyAvailableTaskId);

        when(repository.findByDependsOnTaskId(
                newlyAvailableTaskId))
                .thenReturn(List.of(unresolved));

        service.resolveWaitingForTask(
                newlyAvailableTaskId);

        verify(taskDependencyService)
                .addDependency(
                        unresolved.taskId(),
                        unresolved.dependsOnTaskId(),
                        unresolved.source(),
                        unresolved.sourceRef());

        verify(repository)
                .deleteById(
                        unresolved.id());
    }

    @Test
    void resolveWaitingForTask_whenDependencyAlreadyExists_shouldDeleteUnresolved() {

        UUID taskId = UUID.randomUUID();
        UUID newlyAvailableTaskId = UUID.randomUUID();

        UnresolvedDependency unresolved =
                createUnresolved(
                        taskId,
                        newlyAvailableTaskId);

        when(repository.findByDependsOnTaskId(
                newlyAvailableTaskId))
                .thenReturn(List.of(unresolved));

        when(taskDependencyService.addDependency(
                unresolved.taskId(),
                unresolved.dependsOnTaskId(),
                unresolved.source(),
                unresolved.sourceRef()))
                .thenThrow(
                        new DuplicateDependencyException(
                                "already exists"));

        service.resolveWaitingForTask(
                newlyAvailableTaskId);

        verify(repository)
                .deleteById(
                        unresolved.id());
    }

    @Test
    void resolveWaitingForTask_whenTechnicalFailureOccurs_shouldPropagateAndKeepUnresolved() {

        UUID taskId = UUID.randomUUID();
        UUID newlyAvailableTaskId = UUID.randomUUID();

        UnresolvedDependency unresolved =
                createUnresolved(
                        taskId,
                        newlyAvailableTaskId);

        when(repository.findByDependsOnTaskId(
                newlyAvailableTaskId))
                .thenReturn(List.of(unresolved));

        when(taskDependencyService.addDependency(
                unresolved.taskId(),
                unresolved.dependsOnTaskId(),
                unresolved.source(),
                unresolved.sourceRef()))
                .thenThrow(
                        new RuntimeException(
                                "DB failure"));

        RuntimeException thrown =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                service.resolveWaitingForTask(
                                        newlyAvailableTaskId));

        assertEquals(
                "DB failure",
                thrown.getMessage());

        verify(repository, never())
                .deleteById(
                        unresolved.id());
    }

    @Test
    void findWaitingForTask_shouldReturnMatchingDependencies() {

        UUID dependsOnTaskId = UUID.randomUUID();

        List<UnresolvedDependency> expected =
                List.of(
                        createUnresolved(
                                UUID.randomUUID(),
                                dependsOnTaskId),
                        createUnresolved(
                                UUID.randomUUID(),
                                dependsOnTaskId));

        when(repository.findByDependsOnTaskId(
                dependsOnTaskId))
                .thenReturn(expected);

        List<UnresolvedDependency> result =
                service.findWaitingForTask(
                        dependsOnTaskId);

        assertEquals(
                expected,
                result);
    }

    @Test
    void remove_shouldDeleteById() {

        UUID id = UUID.randomUUID();

        service.remove(id);

        verify(repository)
                .deleteById(id);
    }

    private UnresolvedDependency createUnresolved(
            UUID taskId,
            UUID dependsOnTaskId) {

        return new UnresolvedDependency(
                UUID.randomUUID(),
                taskId,
                dependsOnTaskId,
                DependencyChangeSource.MIND,
                UUID.randomUUID().toString(),
                UnresolvedDependencyService.TARGET_TASK_NOT_FOUND,
                now);
    }
}