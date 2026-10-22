package com.rnd.chain.service;

import com.rnd.chain.dto.Task;
import com.rnd.chain.dto.TaskStatus;
import com.rnd.chain.repository.TaskRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/** Unit test for {@code DependencyEdgeService.insertPlaceholdersIfAbsent}, against a mocked repository. */
class DependencyEdgeServicePlaceholderTest {

    private final TaskRepository taskRepository = mock(TaskRepository.class);
    private final DependencyEdgeService service =
            new DependencyEdgeService(taskRepository, null, null, null, null, null);

    @Test
    void insertsOnePendingEpochPlaceholderForEachSideOfTheEdge() {
        UUID taskId = UUID.randomUUID();
        UUID dependsOnTaskId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();

        service.insertPlaceholdersIfAbsent(taskId, dependsOnTaskId, tenantId);

        ArgumentCaptor<Task> captor = ArgumentCaptor.forClass(Task.class);
        verify(taskRepository, times(2)).insertIfAbsent(captor.capture());
        verifyNoMoreInteractions(taskRepository);

        List<Task> inserted = captor.getAllValues();
        assertThat(inserted).extracting(Task::getId).containsExactly(taskId, dependsOnTaskId);
        assertThat(inserted).allSatisfy(task -> {
            assertThat(task.getTenantId()).isEqualTo(tenantId);
            assertThat(task.getStatus()).isEqualTo(TaskStatus.PENDING);
            assertThat(task.getUpdatedAt()).isEqualTo(LocalDateTime.ofEpochSecond(0, 0, ZoneOffset.UTC));
            assertThat(task.getCreatedAt()).isNotNull();
        });
    }
}
