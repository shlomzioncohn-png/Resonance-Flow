package com.rnd.inbox.task.assignment;
import com.rnd.inbox.shared.exception.ForbiddenActionException;
import com.rnd.inbox.task.dto.TaskAssigneeRequestDto;
import com.rnd.inbox.task.model.Task;
import com.rnd.inbox.team.exception.TeamNotFoundException;
import com.rnd.inbox.team.model.Team;
import com.rnd.inbox.team.repository.TeamRepository;
import com.rnd.inbox.tenant.model.Tenant;
import com.rnd.inbox.user.exception.UserNotFoundException;
import com.rnd.inbox.user.model.User;
import com.rnd.inbox.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssignmentServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-18T09:00:00Z");
    private static final Instant EARLIER = Instant.parse("2026-10-01T09:00:00Z");

    @Mock
    private AssignmentRepository assignmentRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private TeamRepository teamRepository;

    private AssignmentService assignmentService;
    private Tenant tenant;
    private User actor;

    @BeforeEach
    void setUp() {
        assignmentService = new AssignmentService(assignmentRepository,
                new AssignmentMapper(Clock.fixed(NOW, ZoneOffset.UTC)), userRepository, teamRepository);
        tenant = tenant();
        actor = user(tenant);
    }

    @Test
    void resolve_whenListIsNullOrEmpty_returnsEmptyWithoutLookups() {
        assertThat(assignmentService.resolve(null, tenant, actor)).isEmpty();
        assertThat(assignmentService.resolve(List.of(), tenant, actor)).isEmpty();

        verifyNoInteractions(userRepository, teamRepository);
    }

    @Test
    void resolve_withUserAndTeam_buildsAssignmentsAssignedByTheActor() {
        User user = stubUser(user(tenant));
        Team team = stubTeam(team(tenant));

        List<Assignment> result = assignmentService.resolve(List.of(
                request("USER", user.getId(), "ASSIGNEE"),
                request("TEAM", team.getId(), "COLLABORATOR")), tenant, actor);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getUser()).isSameAs(user);
        assertThat(result.get(0).getTeam()).isNull();
        assertThat(result.get(0).getAssignmentRole()).isEqualTo(AssignmentRole.ASSIGNEE);
        assertThat(result.get(0).getAssignedBy()).isSameAs(actor);
        assertThat(result.get(0).getAssignedAt()).isEqualTo(NOW);
        assertThat(result.get(1).getTeam()).isSameAs(team);
        assertThat(result.get(1).getUser()).isNull();
        assertThat(result.get(1).getAssignmentRole()).isEqualTo(AssignmentRole.COLLABORATOR);
    }

    @Test
    void resolve_whenSameUserListedTwice_throwsDuplicateAssigneeException() {
        User user = stubUser(user(tenant));

        assertThatThrownBy(() -> assignmentService.resolve(List.of(
                request("USER", user.getId(), "ASSIGNEE"),
                request("USER", user.getId(), "OWNER")), tenant, actor))
                .isInstanceOf(DuplicateAssigneeException.class);
    }

    @Test
    void resolve_whenUserDoesNotExist_throwsUserNotFoundException() {
        UUID id = UUID.randomUUID();
        when(userRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> assignmentService.resolve(
                List.of(request("USER", id, "ASSIGNEE")), tenant, actor))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void resolve_whenTeamDoesNotExist_throwsTeamNotFoundException() {
        UUID id = UUID.randomUUID();
        when(teamRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> assignmentService.resolve(
                List.of(request("TEAM", id, "ASSIGNEE")), tenant, actor))
                .isInstanceOf(TeamNotFoundException.class);
    }

    @Test
    void resolve_whenUserBelongsToAnotherTenant_throwsForbiddenActionException() {
        User outsider = stubUser(user(tenant()));

        assertThatThrownBy(() -> assignmentService.resolve(
                List.of(request("USER", outsider.getId(), "ASSIGNEE")), tenant, actor))
                .isInstanceOf(ForbiddenActionException.class);
    }

    @Test
    void resolve_whenTeamBelongsToAnotherTenant_throwsForbiddenActionException() {
        Team outsider = stubTeam(team(tenant()));

        assertThatThrownBy(() -> assignmentService.resolve(
                List.of(request("TEAM", outsider.getId(), "ASSIGNEE")), tenant, actor))
                .isInstanceOf(ForbiddenActionException.class);
    }

    @Test
    void replaceAssignments_keepsUnchangedRowUpdatesRoleInPlaceDeletesRemovedAndInsertsNew() {
        Task task = task();
        User kept = user(tenant);
        User roleChanged = user(tenant);
        Team removed = team(tenant);
        User added = user(tenant);
        Assignment keptRow = existingRow(task, kept, null, AssignmentRole.ASSIGNEE);
        Assignment roleChangedRow = existingRow(task, roleChanged, null, AssignmentRole.COLLABORATOR);
        Assignment removedRow = existingRow(task, null, removed, AssignmentRole.RESPONSIBLE);
        when(assignmentRepository.findByTask_Id(task.getId()))
                .thenReturn(List.of(keptRow, roleChangedRow, removedRow));
        when(assignmentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Assignment newRow = desired(added, null, AssignmentRole.ASSIGNEE);
        List<Assignment> result = assignmentService.replaceAssignments(task, List.of(
                desired(kept, null, AssignmentRole.ASSIGNEE),
                desired(roleChanged, null, AssignmentRole.OWNER),
                newRow));

        assertThat(result).containsExactly(keptRow, roleChangedRow, newRow);
        assertThat(keptRow.getAssignedAt()).isEqualTo(EARLIER);
        assertThat(roleChangedRow.getAssignmentRole()).isEqualTo(AssignmentRole.OWNER);
        assertThat(roleChangedRow.getAssignedAt()).isEqualTo(EARLIER);
        assertThat(newRow.getTask()).isSameAs(task);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Assignment>> deleted = ArgumentCaptor.forClass(Collection.class);
        verify(assignmentRepository).deleteAll(deleted.capture());
        assertThat(deleted.getValue()).containsExactly(removedRow);
    }

    @Test
    void replaceAssignments_withEmptyList_deletesEveryCurrentRow() {
        Task task = task();
        Assignment row = existingRow(task, user(tenant), null, AssignmentRole.ASSIGNEE);
        when(assignmentRepository.findByTask_Id(task.getId())).thenReturn(List.of(row));

        List<Assignment> result = assignmentService.replaceAssignments(task, List.of());

        assertThat(result).isEmpty();
        verify(assignmentRepository, never()).save(any());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Assignment>> deleted = ArgumentCaptor.forClass(Collection.class);
        verify(assignmentRepository).deleteAll(deleted.capture());
        assertThat(deleted.getValue()).containsExactly(row);
    }

    @Test
    void findByTasks_groupsAssignmentsByTaskId() {
        Task first = task();
        Task second = task();
        Assignment a1 = existingRow(first, user(tenant), null, AssignmentRole.ASSIGNEE);
        Assignment a2 = existingRow(first, null, team(tenant), AssignmentRole.RESPONSIBLE);
        Assignment a3 = existingRow(second, user(tenant), null, AssignmentRole.OWNER);
        List<UUID> ids = List.of(first.getId(), second.getId());
        when(assignmentRepository.findByTask_IdIn(ids)).thenReturn(List.of(a1, a2, a3));

        Map<UUID, List<Assignment>> result = assignmentService.findByTasks(ids);

        assertThat(result.get(first.getId())).containsExactly(a1, a2);
        assertThat(result.get(second.getId())).containsExactly(a3);
    }

    @Test
    void findByTasks_withNoIds_skipsTheQuery() {
        assertThat(assignmentService.findByTasks(List.of())).isEmpty();

        verifyNoInteractions(assignmentRepository);
    }

    private User stubUser(User user) {
        when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
        return user;
    }

    private Team stubTeam(Team team) {
        when(teamRepository.findById(team.getId())).thenReturn(Optional.of(team));
        return team;
    }

    private static TaskAssigneeRequestDto request(String type, UUID id, String role) {
        TaskAssigneeRequestDto dto = new TaskAssigneeRequestDto();
        dto.setAssigneeType(type);
        dto.setId(id.toString());
        dto.setAssignmentRole(role);
        return dto;
    }

    private static Assignment existingRow(Task task, User user, Team team, AssignmentRole role) {
        Assignment assignment = desired(user, team, role);
        assignment.setId(UUID.randomUUID());
        assignment.setTask(task);
        assignment.setAssignedAt(EARLIER);
        return assignment;
    }

    private static Assignment desired(User user, Team team, AssignmentRole role) {
        Assignment assignment = new Assignment();
        assignment.setUser(user);
        assignment.setTeam(team);
        assignment.setAssignmentRole(role);
        assignment.setAssignedAt(NOW);
        return assignment;
    }

    private static Tenant tenant() {
        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        return tenant;
    }

    private static User user(Tenant tenant) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setTenant(tenant);
        return user;
    }

    private static Team team(Tenant tenant) {
        Team team = new Team();
        team.setId(UUID.randomUUID());
        team.setTenant(tenant);
        return team;
    }

    private static Task task() {
        Task task = new Task();
        task.setId(UUID.randomUUID());
        return task;
    }
}
