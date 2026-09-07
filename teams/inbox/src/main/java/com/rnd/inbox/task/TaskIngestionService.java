package com.rnd.inbox.task;

import com.rnd.inbox.project.Project;
import com.rnd.inbox.project.ProjectNotFoundException;
import com.rnd.inbox.project.ProjectRepository;
import com.rnd.inbox.structuredtask.StructuredTaskEventDto;
import com.rnd.inbox.structuredtask.StructuredTaskMapper;
import com.rnd.inbox.structuredtask.StructuredTaskReferences;
import com.rnd.inbox.taskcreationrequest.TaskCreationRequestEventDto;
import com.rnd.inbox.taskcreationrequest.TaskCreationRequestMapper;
import com.rnd.inbox.team.Team;
import com.rnd.inbox.team.TeamNotFoundException;
import com.rnd.inbox.team.TeamRepository;
import com.rnd.inbox.tenant.Tenant;
import com.rnd.inbox.tenant.TenantNotFoundException;
import com.rnd.inbox.tenant.TenantRepository;
import com.rnd.inbox.user.User;
import com.rnd.inbox.user.UserNotFoundException;
import com.rnd.inbox.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class TaskIngestionService {

    private static final Logger log = LoggerFactory.getLogger(TaskIngestionService.class);

    private final TaskService taskService;
    private final StructuredTaskMapper structuredTaskMapper;
    private final TaskCreationRequestMapper taskCreationRequestMapper;
    private final TenantRepository tenantRepository;
    private final ProjectRepository projectRepository;
    private final UserRepository userRepository;
    private final TeamRepository teamRepository;

    public TaskIngestionService(TaskService taskService,
                                StructuredTaskMapper structuredTaskMapper,
                                TaskCreationRequestMapper taskCreationRequestMapper,
                                TenantRepository tenantRepository,
                                ProjectRepository projectRepository,
                                UserRepository userRepository,
                                TeamRepository teamRepository) {
        this.taskService = taskService;
        this.structuredTaskMapper = structuredTaskMapper;
        this.taskCreationRequestMapper = taskCreationRequestMapper;
        this.tenantRepository = tenantRepository;
        this.projectRepository = projectRepository;
        this.userRepository = userRepository;
        this.teamRepository = teamRepository;
    }

    public Task ingest(StructuredTaskEventDto event) {
        StructuredTaskReferences references = new StructuredTaskReferences(
                requireTenant(event.getTenantId()),
                findProject(event.getProjectId()),
                findUser(event.getAssigneeUserId()),
                findTeam(event.getAssigneeTeamId()),
                findUser(event.getAssignedByUserId()));

        Task task = structuredTaskMapper.map(event, references);
        Assignment assignment = structuredTaskMapper.mapAssignment(event, references);

        return create(task, assignment, correlationId(event.getEventId()));
    }

    public Task ingest(TaskCreationRequestEventDto event) {
        Tenant tenant = requireTenant(event.getTenantId());
        Project project = findProject(event.getProjectId());

        Task task = taskCreationRequestMapper.map(event, tenant, project);

        return create(task, null, correlationId(event.getCorrelationId()));
    }

    private Task create(Task task, Assignment assignment, UUID correlationId) {
        try {
            return taskService.createTask(task, assignment, correlationId);
        } catch (DataIntegrityViolationException e) {
            return taskService.findExisting(task).orElseThrow(() -> e);
        }
    }

    private Tenant requireTenant(String rawId) {
        UUID id = UUID.fromString(rawId);
        return tenantRepository.findById(id)
                .orElseThrow(() -> rejected(new TenantNotFoundException(id)));
    }

    private Project findProject(String rawId) {
        if (rawId == null) {
            return null;
        }
        UUID id = UUID.fromString(rawId);
        return projectRepository.findById(id)
                .orElseThrow(() -> rejected(new ProjectNotFoundException(id)));
    }

    private User findUser(String rawId) {
        if (rawId == null) {
            return null;
        }
        UUID id = UUID.fromString(rawId);
        return userRepository.findById(id)
                .orElseThrow(() -> rejected(new UserNotFoundException(id)));
    }

    private Team findTeam(String rawId) {
        if (rawId == null) {
            return null;
        }
        UUID id = UUID.fromString(rawId);
        return teamRepository.findById(id)
                .orElseThrow(() -> rejected(new TeamNotFoundException(id)));
    }

    private <E extends RuntimeException> E rejected(E exception) {
        log.warn("Rejecting ingestion event: {}", exception.getMessage());
        return exception;
    }

    private UUID correlationId(String rawId) {
        return rawId != null ? UUID.fromString(rawId) : UUID.randomUUID();
    }
}
