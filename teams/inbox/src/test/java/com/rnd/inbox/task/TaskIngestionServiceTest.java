package com.rnd.inbox.task;

import com.rnd.inbox.project.Project;
import com.rnd.inbox.project.ProjectNotFoundException;
import com.rnd.inbox.project.ProjectRepository;
import com.rnd.inbox.structuredtask.StructuredTaskEventDto;
import com.rnd.inbox.structuredtask.StructuredTaskMapper;
import com.rnd.inbox.taskcreationrequest.TaskCreationRequestEventDto;
import com.rnd.inbox.taskcreationrequest.TaskCreationRequestMapper;
import com.rnd.inbox.team.TeamNotFoundException;
import com.rnd.inbox.team.TeamRepository;
import com.rnd.inbox.tenant.Tenant;
import com.rnd.inbox.tenant.TenantNotFoundException;
import com.rnd.inbox.tenant.TenantRepository;
import com.rnd.inbox.user.User;
import com.rnd.inbox.user.UserNotFoundException;
import com.rnd.inbox.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskIngestionServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Mock
    private TaskService taskService;

    @Mock
    private TenantRepository tenantRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TeamRepository teamRepository;

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private TaskIngestionService service;
    private Tenant tenant;

    @BeforeEach
    void setUp() {
        service = new TaskIngestionService(
                taskService,
                new StructuredTaskMapper(clock),
                new TaskCreationRequestMapper(clock),
                tenantRepository,
                projectRepository,
                userRepository,
                teamRepository);
        tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
    }

    @Test
    void ingest_structuredTaskEvent_resolvesTheTenantAndPersistsTheMappedTask() {
        stubTenant();
        StructuredTaskEventDto event = structuredTaskEvent();
        when(taskService.createTask(any(), any(), any())).thenAnswer(i -> i.getArgument(0));

        service.ingest(event);

        ArgumentCaptor<Task> captor = ArgumentCaptor.forClass(Task.class);
        verify(taskService).createTask(captor.capture(), eq(null), eq(UUID.fromString(event.getEventId())));
        assertThat(captor.getValue().getTenant()).isSameAs(tenant);
        assertThat(captor.getValue().getTitle()).isEqualTo("Fix flaky test");
    }

    @Test
    void ingest_structuredTaskEvent_withAnAssignee_passesTheAssignmentAlongsideTheTask() {
        stubTenant();
        User assignee = user();
        when(userRepository.findById(assignee.getId())).thenReturn(Optional.of(assignee));
        when(taskService.createTask(any(), any(), any())).thenAnswer(i -> i.getArgument(0));

        StructuredTaskEventDto event = structuredTaskEvent();
        event.setAssigneeUserId(assignee.getId().toString());
        event.setAssignmentRole("ASSIGNEE");

        service.ingest(event);

        ArgumentCaptor<Assignment> captor = ArgumentCaptor.forClass(Assignment.class);
        verify(taskService).createTask(any(), captor.capture(), any());
        assertThat(captor.getValue().getUser()).isSameAs(assignee);
    }

    @Test
    void ingest_whenTheTenantDoesNotExist_throwsBeforeAnythingIsSaved() {
        UUID tenantId = UUID.randomUUID();
        when(tenantRepository.findById(tenantId)).thenReturn(Optional.empty());

        StructuredTaskEventDto event = structuredTaskEvent();
        event.setTenantId(tenantId.toString());

        assertThatThrownBy(() -> service.ingest(event))
                .isInstanceOf(TenantNotFoundException.class);

        verify(taskService, never()).createTask(any(), any(), any());
    }

    @Test
    void ingest_whenTheProjectDoesNotExist_throwsBeforeAnythingIsSaved() {
        stubTenant();
        UUID projectId = UUID.randomUUID();
        when(projectRepository.findById(projectId)).thenReturn(Optional.empty());

        StructuredTaskEventDto event = structuredTaskEvent();
        event.setProjectId(projectId.toString());

        assertThatThrownBy(() -> service.ingest(event))
                .isInstanceOf(ProjectNotFoundException.class);

        verify(taskService, never()).createTask(any(), any(), any());
    }

    @Test
    void ingest_whenTheAssigneeDoesNotExist_throwsBeforeAnythingIsSaved() {
        stubTenant();
        UUID assigneeId = UUID.randomUUID();
        when(userRepository.findById(assigneeId)).thenReturn(Optional.empty());

        StructuredTaskEventDto event = structuredTaskEvent();
        event.setAssigneeUserId(assigneeId.toString());
        event.setAssignmentRole("ASSIGNEE");

        assertThatThrownBy(() -> service.ingest(event))
                .isInstanceOf(UserNotFoundException.class);

        verify(taskService, never()).createTask(any(), any(), any());
    }

    @Test
    void ingest_whenTheAssigneeTeamDoesNotExist_throwsBeforeAnythingIsSaved() {
        stubTenant();
        UUID teamId = UUID.randomUUID();
        when(teamRepository.findById(teamId)).thenReturn(Optional.empty());

        StructuredTaskEventDto event = structuredTaskEvent();
        event.setAssigneeTeamId(teamId.toString());
        event.setAssignmentRole("OWNER");

        assertThatThrownBy(() -> service.ingest(event))
                .isInstanceOf(TeamNotFoundException.class);

        verify(taskService, never()).createTask(any(), any(), any());
    }

    @Test
    void ingest_taskCreationRequestEvent_resolvesReferencesAndUsesTheEventsCorrelationId() {
        stubTenant();
        Project project = new Project();
        project.setId(UUID.randomUUID());
        when(projectRepository.findById(project.getId())).thenReturn(Optional.of(project));
        when(taskService.createTask(any(), any(), any())).thenAnswer(i -> i.getArgument(0));

        TaskCreationRequestEventDto event = taskCreationRequestEvent();
        event.setProjectId(project.getId().toString());

        service.ingest(event);

        ArgumentCaptor<Task> captor = ArgumentCaptor.forClass(Task.class);
        verify(taskService).createTask(captor.capture(), eq(null),
                eq(UUID.fromString(event.getCorrelationId())));
        assertThat(captor.getValue().getTenant()).isSameAs(tenant);
        assertThat(captor.getValue().getProject()).isSameAs(project);
    }

    @Test
    void ingest_whenAConcurrentDeliveryWonTheInsertRace_returnsTheTaskThatIsAlreadyStored() {
        stubTenant();
        Task alreadyPersisted = new Task();
        alreadyPersisted.setId(UUID.randomUUID());
        when(taskService.createTask(any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("unique violation"));
        when(taskService.findExisting(any())).thenReturn(Optional.of(alreadyPersisted));

        assertThat(service.ingest(structuredTaskEvent())).isSameAs(alreadyPersisted);
    }

    @Test
    void ingest_whenAConstraintIsViolatedForAnotherReason_thePersistenceFailureIsNotSwallowed() {
        stubTenant();
        when(taskService.createTask(any(), any(), any()))
                .thenThrow(new DataIntegrityViolationException("not-null violation"));
        when(taskService.findExisting(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.ingest(structuredTaskEvent()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void stubTenant() {
        when(tenantRepository.findById(tenant.getId())).thenReturn(Optional.of(tenant));
    }

    private User user() {
        User user = new User();
        user.setId(UUID.randomUUID());
        return user;
    }

    private StructuredTaskEventDto structuredTaskEvent() {
        StructuredTaskEventDto dto = new StructuredTaskEventDto();
        dto.setEventId(UUID.randomUUID().toString());
        dto.setTenantId(tenant.getId().toString());
        dto.setTitle("Fix flaky test");
        dto.setSourceType("GITHUB_ISSUE");
        dto.setSourceRef("github:owner/repo:issue:42");
        return dto;
    }

    private TaskCreationRequestEventDto taskCreationRequestEvent() {
        TaskCreationRequestEventDto dto = new TaskCreationRequestEventDto();
        dto.setEventId(UUID.randomUUID().toString());
        dto.setCorrelationId(UUID.randomUUID().toString());
        dto.setTenantId(tenant.getId().toString());
        dto.setTitle("Send the revised pricing deck to Acme");
        dto.setSourceType("SLACK");
        dto.setSourceRef("slack:T012AB3CD:C024BE91L:1756550400.001900");
        dto.setConfidence(0.87);
        dto.setHasDependencies(false);
        return dto;
    }
}
