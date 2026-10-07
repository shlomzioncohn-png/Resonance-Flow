
package com.rnd.chain.service;

import com.rnd.chain.dto.Constraint;
import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.exception.ConstraintNotFoundException;
import com.rnd.chain.exception.TaskNotFoundException;
import com.rnd.chain.repository.ConstraintRepository;
import com.rnd.chain.repository.TaskRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConstraintServiceTest {

    @Mock
    private ConstraintRepository constraintRepository;

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private TaskEligibilityService taskEligibilityService;

    private ConstraintService constraintService;

    @BeforeEach
    void setUp() {
        constraintService = new ConstraintService(
                constraintRepository,
                taskRepository,
                taskEligibilityService
        );
    }

    @Test
    void createConstraint_shouldSaveConstraintAndNotifyEligibilityService() {

        UUID taskId = UUID.randomUUID();
        Task task = createTask(taskId);

        when(taskRepository.findById(taskId))
                .thenReturn(Optional.of(task));

        Constraint created = constraintService.createConstraint(
                taskId,
                Constraint.ConstraintType.APPROVAL_NEEDED,
                Map.of("approver", "manager"),
                "email-123"
        );

        assertNotNull(created);
        assertNotNull(created.getConstraintId());
        assertEquals(taskId, created.getTaskId());

        assertEquals(
                Constraint.ConstraintStatus.PENDING,
                created.getStatus()
        );

        verify(taskEligibilityService).onConstraintAdded(created);
    }

    @Test
    void createConstraint_whenTaskMissing_shouldThrowTaskNotFoundException() {

        UUID taskId = UUID.randomUUID();

        when(taskRepository.findById(taskId))
                .thenReturn(Optional.empty());

        assertThrows(
                TaskNotFoundException.class,
                () -> constraintService.createConstraint(
                        taskId,
                        Constraint.ConstraintType.APPROVAL_NEEDED,
                        Map.of(),
                        "email-123"
                )
        );

        verify(constraintRepository, never()).save(any());
        verify(taskEligibilityService, never()).onConstraintAdded(any());
    }

    @Test
    void updateConstraintStatus_shouldNotifyEligibilityWithOldAndNewStatus() {

        UUID taskId = UUID.randomUUID();

        Constraint constraint = createConstraint(
                "constraint-1",
                taskId,
                Constraint.ConstraintStatus.PENDING
        );

        when(constraintRepository.findById("constraint-1"))
                .thenReturn(Optional.of(constraint));

        Constraint updated = constraintService.updateConstraintStatus(
                taskId,
                "constraint-1",
                Constraint.ConstraintStatus.SATISFIED
        );

        assertEquals(
                Constraint.ConstraintStatus.SATISFIED,
                updated.getStatus()
        );

        verify(taskEligibilityService).onConstraintStatusChanged(
                taskId,
                "constraint-1",
                Constraint.ConstraintStatus.SATISFIED,
                constraint.getReason()
        );
    }

    @Test
    void updateConstraintStatus_whenConstraintMissing_shouldThrow() {

        UUID taskId = UUID.randomUUID();

        when(constraintRepository.findById("missing"))
                .thenReturn(Optional.empty());

        assertThrows(
                ConstraintNotFoundException.class,
                () -> constraintService.updateConstraintStatus(
                        taskId,
                        "missing",
                        Constraint.ConstraintStatus.SATISFIED
                )
        );

        verify(constraintRepository, never()).save(any());
        verifyNoInteractions(taskEligibilityService);
    }

    @Test
    void updateConstraintStatus_whenConstraintBelongsToDifferentTask_shouldThrowNotFound() {

        UUID requestedTaskId = UUID.randomUUID();
        UUID actualTaskId = UUID.randomUUID();

        Constraint constraint = createConstraint(
                "constraint-1",
                actualTaskId,
                Constraint.ConstraintStatus.PENDING
        );

        when(constraintRepository.findById("constraint-1"))
                .thenReturn(Optional.of(constraint));

        assertThrows(
                ConstraintNotFoundException.class,
                () -> constraintService.updateConstraintStatus(
                        requestedTaskId,
                        "constraint-1",
                        Constraint.ConstraintStatus.SATISFIED
                )
        );

        verify(constraintRepository, never()).save(any());
        verifyNoInteractions(taskEligibilityService);
    }

    @Test
    void getTaskConstraints_shouldReturnRepositoryResult() {

        UUID taskId = UUID.randomUUID();
        Task task = createTask(taskId);

        Constraint first = createConstraint(
                "c1",
                taskId,
                Constraint.ConstraintStatus.PENDING
        );

        Constraint second = createConstraint(
                "c2",
                taskId,
                Constraint.ConstraintStatus.SATISFIED
        );

        when(taskRepository.findById(taskId))
                .thenReturn(Optional.of(task));

        when(constraintRepository.findByTaskId(taskId))
                .thenReturn(List.of(first, second));

        List<Constraint> result =
                constraintService.getTaskConstraints(taskId);

        assertEquals(2, result.size());
        assertEquals(first, result.get(0));
        assertEquals(second, result.get(1));

        verify(constraintRepository).findByTaskId(taskId);
    }

    @Test
    void getTaskConstraints_whenTaskMissing_shouldThrowTaskNotFoundException() {

        UUID taskId = UUID.randomUUID();

        when(taskRepository.findById(taskId))
                .thenReturn(Optional.empty());

        assertThrows(
                TaskNotFoundException.class,
                () -> constraintService.getTaskConstraints(taskId)
        );

        verify(constraintRepository, never()).findByTaskId(any());
    }

    private Task createTask(UUID id) {

        LocalDateTime now = LocalDateTime.now();

        return Task.builder()
                .id(id)
                .parentTaskId(null)
                .ownerId("test-owner")
                .status(TaskStatus.ACTIVE)
                .isReady(true)
                .blockingCount(0)
                .openConstraintCount(0)
                .estimatedDurationMin(null)
                .score(null)
                .createdAt(now)
                .updatedAt(now)
                .deletedAt(null)
                .build();
    }

    private Constraint createConstraint(
            String id,
            UUID taskId,
            Constraint.ConstraintStatus status) {

        LocalDateTime now = LocalDateTime.now();

        return new Constraint(
                id,
                taskId,
                Constraint.ConstraintType.APPROVAL_NEEDED,
                status,
                Map.of("approver", "manager"),
                "test-source",
                now,
                now
        );
    }
}