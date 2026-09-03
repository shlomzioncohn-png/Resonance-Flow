package com.rnd.chain.service;

import com.rnd.chain.dto.Constraint;
import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.repository.ConstraintRepository;
import com.rnd.chain.repository.TaskDependencyRepository;
import com.rnd.chain.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.*;

class TaskEligibilityServiceTest {

    private TaskRepository taskRepository;
    private TaskDependencyRepository taskDependencyRepository;
    private ConstraintRepository constraintRepository;
    private SchedulingUpdateService schedulingUpdateService;

    private TaskEligibilityService service;

    @BeforeEach
    void setUp() {
        taskRepository = mock(TaskRepository.class);
        taskDependencyRepository = mock(TaskDependencyRepository.class);
        constraintRepository = mock(ConstraintRepository.class);
        schedulingUpdateService = mock(SchedulingUpdateService.class);

        service = new TaskEligibilityService(
                taskRepository,
                taskDependencyRepository,
                constraintRepository,
                schedulingUpdateService
        );
    }

    @Test
    void recalculateTaskEligibility_whenReadyChanges_shouldPublishSchedulingUpdate() {
        UUID taskId = UUID.randomUUID();

        Task task = new Task(
                taskId,
                null,
                TaskStatus.ACTIVE,
                false,
                0,
                0,
                null,
                null,
                LocalDateTime.now(),
                LocalDateTime.now(),
                null
        );

        when(taskRepository.findById(taskId))
                .thenReturn(Optional.of(task));

        when(constraintRepository.findByTaskId(taskId))
                .thenReturn(Collections.emptyList());

        service.recalculateTaskEligibility(taskId);

        verify(taskRepository).save(task);
        verify(schedulingUpdateService).publishSchedulingUpdate(task);
    }
}