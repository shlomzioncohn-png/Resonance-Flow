package com.rnd.chain.tenant;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.exception.TaskNotFoundException;
import com.rnd.chain.repository.TaskRepository;
import com.rnd.chain.testsupport.TaskFixtures;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TaskOwnershipServiceTest {

    private static final UUID TASK_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    private final TaskRepository taskRepository = mock(TaskRepository.class);
    private final TaskOwnershipService service = new TaskOwnershipService(taskRepository);

    @Test
    void matchingTenant_passes() {
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(TaskFixtures.newTask(TASK_ID)));

        assertDoesNotThrow(() -> service.requireTaskInTenant(TASK_ID, TaskFixtures.DEFAULT_TENANT_ID));
    }

    @Test
    void otherTenant_failsLikeAMissingTask() {
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(TaskFixtures.newTask(TASK_ID)));
        TaskNotFoundException otherTenant = assertThrows(TaskNotFoundException.class,
                () -> service.requireTaskInTenant(TASK_ID, TaskFixtures.OTHER_TENANT_ID));

        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.empty());
        TaskNotFoundException missing = assertThrows(TaskNotFoundException.class,
                () -> service.requireTaskInTenant(TASK_ID, TaskFixtures.OTHER_TENANT_ID));

        assertEquals(missing.getMessage(), otherTenant.getMessage());
    }

    @Test
    void taskWithNullTenant_isNotFound() {
        Task legacy = Task.builder()
                .id(TASK_ID)
                .status(TaskStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(legacy));

        assertThrows(TaskNotFoundException.class,
                () -> service.requireTaskInTenant(TASK_ID, TaskFixtures.DEFAULT_TENANT_ID));
    }

    @Test
    void nullTenantId_throwsInsteadOfSkippingTheCheck() {
        assertThrows(NullPointerException.class, () -> service.requireTaskInTenant(TASK_ID, null));
    }
}
