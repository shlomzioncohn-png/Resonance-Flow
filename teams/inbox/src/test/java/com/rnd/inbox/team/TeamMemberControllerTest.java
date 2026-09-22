package com.rnd.inbox.team;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.inbox.shared.config.AppConfig;
import com.rnd.inbox.shared.config.WebMvcConfig;
import com.rnd.inbox.user.User;
import com.rnd.inbox.user.UserRepository;
import com.rnd.inbox.user.UserRole;
import com.rnd.inbox.user.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TeamMemberController.class)
@Import({AppConfig.class, WebMvcConfig.class})
class TeamMemberControllerTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TeamMemberService teamMemberService;

    @MockBean
    private UserRepository userRepository;

    private UUID teamId;
    private UUID actorId;

    @BeforeEach
    void setUp() {
        teamId = UUID.randomUUID();
        actorId = UUID.randomUUID();

        stubActorRole(UserRole.SYSTEM_ADMIN);
    }

    private void stubActorRole(UserRole role) {
        User actorEntity = new User();
        actorEntity.setId(actorId);
        actorEntity.setRole(role);
        actorEntity.setStatus(UserStatus.ACTIVE);
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actorEntity));
    }

    @Test
    void listMembers_returns200WithMemberList() throws Exception {
        TeamMemberResponseDto member = new TeamMemberResponseDto(UUID.randomUUID(), Instant.now(), null);
        when(teamMemberService.listMembers(teamId, actorId)).thenReturn(List.of(member));

        mockMvc.perform(get("/api/v1/teams/{id}/members", teamId).header(ACTOR_HEADER, actorId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].userId").value(member.getUserId().toString()));
    }

    @Test
    void listMembers_withoutActorHeader_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/teams/{id}/members", teamId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
    }

    @Test
    void addMember_withValidRequest_returns201() throws Exception {
        TeamMemberRequestDto request = new TeamMemberRequestDto();
        UUID userId = UUID.randomUUID();
        request.setUserId(userId.toString());
        TeamMemberResponseDto response = new TeamMemberResponseDto(userId, Instant.now(), null);
        when(teamMemberService.addMember(eq(teamId), any(), eq(actorId))).thenReturn(response);

        mockMvc.perform(post("/api/v1/teams/{id}/members", teamId)
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(userId.toString()));
    }

    @Test
    void addMember_whenActorRoleIsEmployee_returns403WithoutCallingService() throws Exception {
        stubActorRole(UserRole.EMPLOYEE);
        TeamMemberRequestDto request = new TeamMemberRequestDto();
        request.setUserId(UUID.randomUUID().toString());

        mockMvc.perform(post("/api/v1/teams/{id}/members", teamId)
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));

        verify(teamMemberService, never()).addMember(any(), any(), any());
    }

    @Test
    void addMember_whenAlreadyMember_returns409() throws Exception {
        UUID userId = UUID.randomUUID();
        TeamMemberRequestDto request = new TeamMemberRequestDto();
        request.setUserId(userId.toString());
        when(teamMemberService.addMember(eq(teamId), any(), eq(actorId)))
                .thenThrow(new DuplicateTeamMembershipException(teamId, userId));

        mockMvc.perform(post("/api/v1/teams/{id}/members", teamId)
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DUPLICATE_TEAM_MEMBERSHIP"));
    }

    @Test
    void removeMember_withActiveMembership_returns204() throws Exception {
        UUID userId = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/teams/{id}/members/{userId}", teamId, userId)
                        .header(ACTOR_HEADER, actorId.toString()))
                .andExpect(status().isNoContent());
    }

    @Test
    void removeMember_whenNotAMember_returns404() throws Exception {
        UUID userId = UUID.randomUUID();
        doThrow(new TeamMembershipNotFoundException(teamId, userId))
                .when(teamMemberService).removeMember(teamId, userId, actorId);

        mockMvc.perform(delete("/api/v1/teams/{id}/members/{userId}", teamId, userId)
                        .header(ACTOR_HEADER, actorId.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("TEAM_MEMBERSHIP_NOT_FOUND"));
    }

    @Test
    void removeMember_whenActorRoleIsEmployee_returns403WithoutCallingService() throws Exception {
        stubActorRole(UserRole.EMPLOYEE);
        UUID userId = UUID.randomUUID();

        mockMvc.perform(delete("/api/v1/teams/{id}/members/{userId}", teamId, userId)
                        .header(ACTOR_HEADER, actorId.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));

        verify(teamMemberService, never()).removeMember(any(), any(), any());
    }
}
