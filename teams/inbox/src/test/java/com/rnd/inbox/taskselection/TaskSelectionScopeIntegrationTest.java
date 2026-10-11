package com.rnd.inbox.taskselection;

import com.rnd.inbox.support.PostgresIntegrationTest;
import com.rnd.inbox.task.assignment.Assignment;
import com.rnd.inbox.task.assignment.AssignmentRepository;
import com.rnd.inbox.task.assignment.AssignmentRole;
import com.rnd.inbox.task.model.SourceType;
import com.rnd.inbox.task.model.Task;
import com.rnd.inbox.task.model.TaskExtractionProvenance;
import com.rnd.inbox.task.model.TaskStatus;
import com.rnd.inbox.task.repository.TaskRepository;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TaskSelectionScopeIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    private TaskSelectionService taskSelectionService;

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

    // Only the tenants this test created are removed - see PostgresIntegrationTest.
    @AfterEach
    void cleanUp() {
        deleteTenantData(createdTenantIds);
    }

    @Test
    void selectTasksForWindow_scopeEligibility_directOrActiveTeamMembershipRequired() {
        Instant now = Instant.now();

        Tenant tenant = persistTenant();
        User employee = persistUser(tenant, "employee-" + UUID.randomUUID());
        User otherUser = persistUser(tenant, "other-" + UUID.randomUUID());
        User teamLead = persistUser(tenant, "lead-" + UUID.randomUUID());

        Team teamA = persistTeam(tenant, teamLead, "Team A");
        Team teamB = persistTeam(tenant, teamLead, "Team B");
        persistActiveMembership(teamA, employee, now);
        persistActiveMembership(teamB, otherUser, now);

        // T1 — assigned directly to the employee
        Task t1 = persistTask(tenant, "T1", now);
        persistAssignment(t1, employee, null, now);

        // T2 — assigned to a different user
        Task t2 = persistTask(tenant, "T2", now);
        persistAssignment(t2, otherUser, null, now);

        // T3 — assigned to Team A, of which the employee is an active member
        Task t3 = persistTask(tenant, "T3", now);
        persistAssignment(t3, null, teamA, now);

        // T4 — assigned to Team B, of which the employee is not a member
        Task t4 = persistTask(tenant, "T4", now);
        persistAssignment(t4, null, teamB, now);

        // T5 — assigned to a different user; owner_id is the employee, but ownership is not assignment
        Task t5 = persistTask(tenant, "T5", now);
        t5.setOwner(employee);
        taskRepository.saveAndFlush(t5);
        persistAssignment(t5, otherUser, null, now);

        TaskSelectionResponseDto result = taskSelectionService.selectTasks(tenant.getId(), employee.getId(), 60);

        assertThat(result.getSelectedTasks())
                .extracting(SelectedTaskDto::getTitle)
                .containsExactlyInAnyOrder("T1", "T3");
    }

    private Tenant persistTenant() {
        Tenant tenant = new Tenant();
        tenant.setName("Acme");
        tenant.setSlug("acme-" + UUID.randomUUID());
        tenant.setStatus(TenantStatus.ACTIVE);
        tenant.setCreatedAt(Instant.now());
        tenant.setUpdatedAt(Instant.now());
        Tenant saved = tenantRepository.saveAndFlush(tenant);
        createdTenantIds.add(saved.getId());
        return saved;
    }

    private User persistUser(Tenant tenant, String username) {
        User user = new User();
        user.setTenant(tenant);
        user.setDisplayName(username);
        user.setUsername(username);
        user.setRole(UserRole.EMPLOYEE);
        user.setStatus(UserStatus.ACTIVE);
        user.setCreatedAt(Instant.now());
        user.setUpdatedAt(Instant.now());
        return userRepository.saveAndFlush(user);
    }

    private Team persistTeam(Tenant tenant, User lead, String name) {
        Team team = new Team();
        team.setTenant(tenant);
        team.setName(name);
        team.setTeamLead(lead);
        team.setStatus(TeamStatus.ACTIVE);
        team.setCreatedAt(Instant.now());
        team.setUpdatedAt(Instant.now());
        return teamRepository.saveAndFlush(team);
    }

    private void persistActiveMembership(Team team, User user, Instant joinedAt) {
        TeamMember member = new TeamMember();
        member.setId(new TeamMemberId(team.getId(), user.getId()));
        member.setTeam(team);
        member.setUser(user);
        member.setJoinedAt(joinedAt);
        teamMemberRepository.saveAndFlush(member);
    }

    private Task persistTask(Tenant tenant, String title, Instant now) {
        Task task = new Task();
        task.setTenant(tenant);
        task.setTitle(title);
        task.setSourceType(SourceType.MANUAL);
        task.setStatus(TaskStatus.APPROVED);
        task.setAvailable(true);
        task.setPriorityScore(5f);
        task.setEffortEstimateMinutes(15);
        task.setEffortEstimateConfidence(0.9f);
        task.setExtractionProvenance(TaskExtractionProvenance.MANUAL);
        task.setCreatedAt(now);
        task.setUpdatedAt(now);
        return taskRepository.saveAndFlush(task);
    }

    private void persistAssignment(Task task, User user, Team team, Instant assignedAt) {
        Assignment assignment = new Assignment();
        assignment.setTask(task);
        assignment.setUser(user);
        assignment.setTeam(team);
        assignment.setAssignmentRole(AssignmentRole.ASSIGNEE);
        assignment.setAssignedAt(assignedAt);
        assignmentRepository.saveAndFlush(assignment);
    }
}
