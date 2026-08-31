package com.rnd.inbox.service;

import com.rnd.inbox.event.EventPublisher;
import com.rnd.inbox.model.entity.Organization;
import com.rnd.inbox.model.entity.Task;
import com.rnd.inbox.model.enums.SourceType;
import com.rnd.inbox.model.enums.TaskExtractionProvenance;
import com.rnd.inbox.model.enums.TaskStatus;
import com.rnd.inbox.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    private static final String SOURCE_SERVICE = "inbox";

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private AuditService auditService;

    @Mock
    private EventPublisher eventPublisher;

    private TaskService taskService;
    private Organization organization;
    private UUID organizationId;

    @BeforeEach
    void setUp() {
        taskService = new TaskService(
                taskRepository,
                auditService,
                eventPublisher,
                SOURCE_SERVICE);
        organizationId = UUID.randomUUID();
        organization = new Organization();
        organization.setId(organizationId);
    }

    private Task incomingTask() {
        Task task = new Task();
        task.setOrganization(organization);
        task.setTitle("Fix flaky test");
        task.setSourceType(SourceType.GITHUB_ISSUE);
        task.setSourceRef("github:owner/repo:issue:42");
        task.setStatus(TaskStatus.NEW);
        task.setExtractionProvenance(TaskExtractionProvenance.ETL);
        return task;
    }

    @Test
    void createsTaskAndAuditWhenNoneExistsYet() {
        Task incoming = incomingTask();
        UUID correlationId = UUID.randomUUID();
        UUID generatedId = UUID.randomUUID();

        when(taskRepository.findByOrganization_IdAndSourceTypeAndSourceRef(
                organizationId, SourceType.GITHUB_ISSUE, "github:owner/repo:issue:42"))
                .thenReturn(Optional.empty());
        when(taskRepository.saveAndFlush(incoming)).thenAnswer(invocation -> {
            incoming.setId(generatedId);
            return incoming;
        });

        Task result = taskService.createTask(incoming, correlationId);

        assertThat(result.getId()).isEqualTo(generatedId);
        verify(taskRepository).saveAndFlush(incoming);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> snapshotCaptor = ArgumentCaptor.forClass(Map.class);
        verify(auditService).createAuditLog(eq("TASK_CREATED"), eq("Task"), eq(generatedId), eq(organization),
                snapshotCaptor.capture(), eq(correlationId));
        assertThat(snapshotCaptor.getValue())
                .containsEntry("title", "Fix flaky test")
                .containsEntry("sourceType", "GITHUB_ISSUE")
                .containsEntry("sourceRef", "github:owner/repo:issue:42")
                .containsEntry("status", "NEW");
    }

    @Test
    void doesNotCreateAnotherTaskWhenOneAlreadyExists() {
        Task incoming = incomingTask();
        Task existing = incomingTask();
        existing.setId(UUID.randomUUID());

        when(taskRepository.findByOrganization_IdAndSourceTypeAndSourceRef(
                organizationId, SourceType.GITHUB_ISSUE, "github:owner/repo:issue:42"))
                .thenReturn(Optional.of(existing));

        Task result = taskService.createTask(incoming, UUID.randomUUID());

        assertThat(result).isSameAs(existing);
        verify(taskRepository, never()).saveAndFlush(any());
        verify(auditService, never()).createAuditLog(any(), any(), any(), any(), any(), any());
    }
}
