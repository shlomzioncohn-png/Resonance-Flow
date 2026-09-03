package com.rnd.chain.service;

import com.rnd.chain.dto.Constraint;
import com.rnd.chain.exception.ConstraintNotFoundException;
import com.rnd.chain.repository.ConstraintRepository;
import com.rnd.chain.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class ConstraintServiceTest {

    private ConstraintRepository constraintRepository;
    private TaskRepository taskRepository;
    private TaskEligibilityService taskEligibilityService;

    private ConstraintService constraintService;

    @BeforeEach
    void setUp() {
        constraintRepository = mock(ConstraintRepository.class);
        taskRepository = mock(TaskRepository.class);
        taskEligibilityService = mock(TaskEligibilityService.class);

        constraintService = new ConstraintService(
                constraintRepository,
                taskRepository,
                taskEligibilityService
        );
    }

    @Test
    void updateConstraintStatus_whenConstraintBelongsToDifferentTask_shouldThrowNotFound() {

        UUID requestedTaskId = UUID.randomUUID();
        UUID actualTaskId = UUID.randomUUID();

        String constraintId = UUID.randomUUID().toString();

        Constraint constraint = new Constraint(
                constraintId,
                actualTaskId,
                Constraint.ConstraintType.APPROVAL_NEEDED,
                Constraint.ConstraintStatus.PENDING,
                Map.of(),
                "test-source",
                LocalDateTime.now(),
                LocalDateTime.now()
        );

        when(constraintRepository.findById(constraintId))
                .thenReturn(Optional.of(constraint));

        assertThrows(
                ConstraintNotFoundException.class,
                () -> constraintService.updateConstraintStatus(
                        requestedTaskId,
                        constraintId,
                        Constraint.ConstraintStatus.SATISFIED
                )
        );

        verify(constraintRepository, never()).save(any());
        verify(taskEligibilityService, never())
                .recalculateTaskEligibility(any());
    }
}