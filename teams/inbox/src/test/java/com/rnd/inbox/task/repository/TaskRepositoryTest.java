package com.rnd.inbox.task.repository;

import com.rnd.inbox.support.PostgresIntegrationTest;
import com.rnd.inbox.task.assignment.Assignment;
import com.rnd.inbox.task.assignment.AssignmentRepository;
import com.rnd.inbox.task.assignment.AssignmentRole;
import com.rnd.inbox.task.model.SourceType;
import com.rnd.inbox.task.model.Task;
import com.rnd.inbox.task.model.TaskExtractionProvenance;
import com.rnd.inbox.task.model.TaskStatus;
import com.rnd.inbox.team.member.TeamMember;
import com.rnd.inbox.team.member.TeamMemberId;
import com.rnd.inbox.team.member.TeamMemberRepository;
import com.rnd.inbox.team.model.Team;
import com.rnd.inbox.team.model.TeamStatus;
import com.rnd.inbox.team.repository.TeamRepository;
import com.rnd.inbox.tenant.model.Tenant;
import com.rnd.inbox.tenant.model.TenantStatus;
import com.rnd.inbox.tenant.repository.TenantRepository;
import com.rnd.inbox.user.model.User;
import com.rnd.inbox.user.model.UserRole;
import com.rnd.inbox.user.model.UserStatus;
import com.rnd.inbox.user.repository.UserRepository;
import com.rnd.inbox.workinsights.CompletedTaskRow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TaskRepositoryTest extends PostgresIntegrationTest {

    private static final List<AssignmentRole> ROLES =
            List.of(AssignmentRole.OWNER, AssignmentRole.ASSIGNEE, AssignmentRole.RESPONSIBLE);
    private static final List<TaskStatus> OPEN_STATUSES = List.of(
            TaskStatus.NEW, TaskStatus.IN_REVIEW, TaskStatus.APPROVED, TaskStatus.IN_PROGRESS, TaskStatus.SNOOZED);

    private static final Instant FROM = Instant.parse("2026-08-08T00:00:00Z");
    private static final Instant TO = Instant.parse("2026-10-07T00:00:00Z");
    private static final Instant IN_RANGE = Instant.parse("2026-09-15T10:00:00Z");
    private static final Instant DEADLINE = Instant.parse("2026-09-16T12:00:00Z");

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TeamRepository teamRepository;

    @Autowired
    private TeamMemberRepository teamMemberRepository;

    @Autowired
    private AssignmentRepository assignmentRepository;

    private final List<UUID> createdTenantIds = new ArrayList<>();

    private Tenant tenant;
    private User employee;
    private User otherUser;

    @BeforeEach
    void setUp() {
        tenant = persistTenant();
        employee = persistUser(tenant, "employee-" + UUID.randomUUID());
        otherUser = persistUser(tenant, "other-" + UUID.randomUUID());
    }

    @AfterEach
    void cleanUp() {
        deleteTenantData(createdTenantIds);
    }

    @Test
    void findCompletedForUser_returnsTasksOwnedByOrPersonallyAssignedToUserInAllowedRoles() {
        persistDoneTask(tenant, employee, 1);
        persistAssignment(persistDoneTask(tenant, null, 2), employee, null, AssignmentRole.OWNER);
        persistAssignment(persistDoneTask(tenant, null, 3), employee, null, AssignmentRole.ASSIGNEE);
        persistAssignment(persistDoneTask(tenant, null, 4), employee, null, AssignmentRole.RESPONSIBLE);
        persistAssignment(persistDoneTask(tenant, employee, 5), employee, null, AssignmentRole.ASSIGNEE);

        persistAssignment(persistDoneTask(tenant, null, 6), employee, null, AssignmentRole.COLLABORATOR);
        persistAssignment(persistDoneTask(tenant, otherUser, 7), otherUser, null, AssignmentRole.ASSIGNEE);
        Team team = persistTeamWithMember(tenant, otherUser, employee);
        persistAssignment(persistDoneTask(tenant, null, 8), null, team, AssignmentRole.ASSIGNEE);

        List<CompletedTaskRow> rows =
                taskRepository.findCompletedForUser(tenant.getId(), employee.getId(), ROLES, TaskStatus.DONE, FROM, TO);

        assertThat(rows).extracting(CompletedTaskRow::effortEstimateMinutes)
                .containsExactlyInAnyOrder(1, 2, 3, 4, 5);
    }

    @Test
    void findCompletedForUser_returnsOnlyDoneTasksCompletedInsideTheRange() {
        persistTask(tenant, employee, TaskStatus.DONE, FROM, DEADLINE, 1);
        persistTask(tenant, employee, TaskStatus.DONE, FROM.minusSeconds(1), DEADLINE, 2);
        persistTask(tenant, employee, TaskStatus.DONE, TO, DEADLINE, 3);
        persistTask(tenant, employee, TaskStatus.IN_PROGRESS, IN_RANGE, DEADLINE, 4);
        persistTask(tenant, employee, TaskStatus.DONE, IN_RANGE, null, null);

        List<CompletedTaskRow> rows =
                taskRepository.findCompletedForUser(tenant.getId(), employee.getId(), ROLES, TaskStatus.DONE, FROM, TO);

        assertThat(rows).containsExactlyInAnyOrder(
                new CompletedTaskRow(FROM, DEADLINE, 1),
                new CompletedTaskRow(IN_RANGE, null, null));
    }

    @Test
    void findCompletedForUser_whenTaskIsInAnotherTenant_doesNotReturnIt() {
        Tenant otherTenant = persistTenant();
        persistDoneTask(otherTenant, employee, 1);

        List<CompletedTaskRow> rows =
                taskRepository.findCompletedForUser(tenant.getId(), employee.getId(), ROLES, TaskStatus.DONE, FROM, TO);

        assertThat(rows).isEmpty();
    }

    @Test
    void findOpenDeadlinesForUser_returnsDeadlineOfEveryOpenTaskOfTheUser() {
        Instant laterDeadline = DEADLINE.plus(Duration.ofDays(3));
        persistTask(tenant, employee, TaskStatus.NEW, null, DEADLINE, 1);
        persistTask(tenant, employee, TaskStatus.IN_PROGRESS, null, null, 2);
        Task assigned = persistTask(tenant, null, TaskStatus.APPROVED, null, laterDeadline, 3);
        persistAssignment(assigned, employee, null, AssignmentRole.RESPONSIBLE);

        persistTask(tenant, employee, TaskStatus.DONE, IN_RANGE, DEADLINE, 4);
        persistTask(tenant, employee, TaskStatus.REJECTED, null, DEADLINE, 5);
        persistTask(tenant, employee, TaskStatus.CANCELLED, null, DEADLINE, 6);
        Task collaborated = persistTask(tenant, null, TaskStatus.NEW, null, DEADLINE, 7);
        persistAssignment(collaborated, employee, null, AssignmentRole.COLLABORATOR);
        persistTask(tenant, otherUser, TaskStatus.NEW, null, DEADLINE, 8);

        List<Instant> deadlines =
                taskRepository.findOpenDeadlinesForUser(tenant.getId(), employee.getId(), ROLES, OPEN_STATUSES);

        assertThat(deadlines).containsExactlyInAnyOrder(DEADLINE, null, laterDeadline);
    }

    private Task persistDoneTask(Tenant taskTenant, User owner, Integer effortEstimateMinutes) {
        return persistTask(taskTenant, owner, TaskStatus.DONE, IN_RANGE, DEADLINE, effortEstimateMinutes);
    }

    private Task persistTask(Tenant taskTenant, User owner, TaskStatus status, Instant completedAt,
                             Instant deadline, Integer effortEstimateMinutes) {
        Task task = new Task();
        task.setTenant(taskTenant);
        task.setOwner(owner);
        task.setTitle("Task " + effortEstimateMinutes);
        task.setSourceType(SourceType.MANUAL);
        task.setStatus(status);
        task.setCompletedAt(completedAt);
        task.setDeadline(deadline);
        task.setEffortEstimateMinutes(effortEstimateMinutes);
        task.setExtractionProvenance(TaskExtractionProvenance.MANUAL);
        task.setCreatedAt(FROM);
        task.setUpdatedAt(FROM);
        return taskRepository.saveAndFlush(task);
    }

    private void persistAssignment(Task task, User user, Team team, AssignmentRole role) {
        Assignment assignment = new Assignment();
        assignment.setTask(task);
        assignment.setUser(user);
        assignment.setTeam(team);
        assignment.setAssignmentRole(role);
        assignment.setAssignedAt(FROM);
        assignmentRepository.saveAndFlush(assignment);
    }

    private Team persistTeamWithMember(Tenant teamTenant, User lead, User member) {
        Team team = new Team();
        team.setTenant(teamTenant);
        team.setName("Team");
        team.setTeamLead(lead);
        team.setStatus(TeamStatus.ACTIVE);
        team.setCreatedAt(FROM);
        team.setUpdatedAt(FROM);
        Team saved = teamRepository.saveAndFlush(team);

        TeamMember teamMember = new TeamMember();
        teamMember.setId(new TeamMemberId(saved.getId(), member.getId()));
        teamMember.setTeam(saved);
        teamMember.setUser(member);
        teamMember.setJoinedAt(FROM);
        teamMemberRepository.saveAndFlush(teamMember);
        return saved;
    }

    private Tenant persistTenant() {
        Tenant newTenant = new Tenant();
        newTenant.setName("Acme");
        newTenant.setSlug("acme-" + UUID.randomUUID());
        newTenant.setStatus(TenantStatus.ACTIVE);
        newTenant.setCreatedAt(FROM);
        newTenant.setUpdatedAt(FROM);
        Tenant saved = tenantRepository.saveAndFlush(newTenant);
        createdTenantIds.add(saved.getId());
        return saved;
    }

    private User persistUser(Tenant userTenant, String username) {
        User user = new User();
        user.setTenant(userTenant);
        user.setDisplayName(username);
        user.setUsername(username);
        user.setRole(UserRole.EMPLOYEE);
        user.setStatus(UserStatus.ACTIVE);
        user.setCreatedAt(FROM);
        user.setUpdatedAt(FROM);
        return userRepository.saveAndFlush(user);
    }
}
