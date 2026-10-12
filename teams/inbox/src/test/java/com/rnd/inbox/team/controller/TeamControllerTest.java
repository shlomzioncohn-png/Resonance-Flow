package com.rnd.inbox.team.controller;
import com.rnd.inbox.team.dto.TeamCreateRequestDto;
import com.rnd.inbox.team.dto.TeamResponseDto;
import com.rnd.inbox.team.dto.TeamStatusUpdateRequestDto;
import com.rnd.inbox.team.dto.TeamUpdateRequestDto;
import com.rnd.inbox.team.exception.TeamNotFoundException;
import com.rnd.inbox.team.model.Team;
import com.rnd.inbox.team.model.TeamStatus;
import com.rnd.inbox.team.service.TeamService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.inbox.shared.config.AppConfig;
import com.rnd.inbox.shared.config.WebMvcConfig;
import com.rnd.inbox.shared.dto.PageResponseDto;
import com.rnd.inbox.shared.exception.ForbiddenActionException;
import com.rnd.inbox.tenant.exception.TenantNotFoundException;
import com.rnd.inbox.user.model.User;
import com.rnd.inbox.user.repository.UserRepository;
import com.rnd.inbox.user.model.UserRole;
import com.rnd.inbox.user.model.UserStatus;
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

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TeamController.class)
@Import({AppConfig.class, WebMvcConfig.class})
class TeamControllerTest {

    private static final String ACTOR_HEADER = "X-Actor-Id";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TeamService teamService;

    @MockBean
    private UserRepository userRepository;

    private UUID teamId;
    private UUID tenantId;
    private UUID actorId;

    @BeforeEach
    void setUp() {
        teamId = UUID.randomUUID();
        tenantId = UUID.randomUUID();
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

    private TeamResponseDto sampleTeam() {
        return new TeamResponseDto(teamId, null, "Platform Team", UUID.randomUUID(), TeamStatus.ACTIVE,
                Instant.now(), Instant.now());
    }

    private TeamCreateRequestDto validCreateRequest() {
        TeamCreateRequestDto request = new TeamCreateRequestDto();
        request.setTenantId(tenantId.toString());
        request.setName("Platform Team");
        request.setTeamLeadId(UUID.randomUUID().toString());
        return request;
    }

    private TeamUpdateRequestDto validUpdateRequest() {
        TeamUpdateRequestDto request = new TeamUpdateRequestDto();
        request.setName("New Name");
        return request;
    }

    @Test
    void createTeam_withValidRequest_returns201WithLocationHeader() throws Exception {
        when(teamService.createTeam(any(), eq(actorId))).thenReturn(sampleTeam());

        mockMvc.perform(post("/api/v1/teams")
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validCreateRequest())))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", containsString("/api/v1/teams/" + teamId)))
                .andExpect(jsonPath("$.id").value(teamId.toString()));
    }

    @Test
    void createTeam_withMissingRequiredFields_returns400WithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/v1/teams")
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TeamCreateRequestDto())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void createTeam_withoutActorHeader_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/teams")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validCreateRequest())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
    }

    @Test
    void createTeam_whenActorRoleIsEmployee_returns403WithoutCallingService() throws Exception {
        stubActorRole(UserRole.EMPLOYEE);

        mockMvc.perform(post("/api/v1/teams")
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validCreateRequest())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));

        verify(teamService, never()).createTeam(any(), any());
    }

    @Test
    void createTeam_whenTenantNotFound_returns404() throws Exception {
        when(teamService.createTeam(any(), eq(actorId))).thenThrow(new TenantNotFoundException(tenantId));

        mockMvc.perform(post("/api/v1/teams")
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validCreateRequest())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("TENANT_NOT_FOUND"));
    }

    @Test
    void getTeamById_whenFound_returns200() throws Exception {
        when(teamService.getTeamById(teamId, actorId)).thenReturn(sampleTeam());

        mockMvc.perform(get("/api/v1/teams/{id}", teamId).header(ACTOR_HEADER, actorId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(teamId.toString()));
    }

    @Test
    void getTeamById_withoutActorHeader_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/teams/{id}", teamId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
    }

    @Test
    void getTeamById_whenNotFound_returns404() throws Exception {
        when(teamService.getTeamById(teamId, actorId)).thenThrow(new TeamNotFoundException(teamId));

        mockMvc.perform(get("/api/v1/teams/{id}", teamId).header(ACTOR_HEADER, actorId.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("TEAM_NOT_FOUND"));
    }

    @Test
    void getTeamById_whenActorOutsideScope_returns403() throws Exception {
        when(teamService.getTeamById(teamId, actorId))
                .thenThrow(new ForbiddenActionException("Actor cannot act on team " + teamId));

        mockMvc.perform(get("/api/v1/teams/{id}", teamId).header(ACTOR_HEADER, actorId.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    @Test
    void listTeams_returns200WithPageResponse() throws Exception {
        PageResponseDto<TeamResponseDto> page = new PageResponseDto<>(List.of(sampleTeam()), 0, 20, 1, 1);
        when(teamService.listTeams(any(), eq(actorId))).thenReturn(page);

        mockMvc.perform(get("/api/v1/teams").header(ACTOR_HEADER, actorId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(teamId.toString()));
    }

    @Test
    void listTeams_withoutActorHeader_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/teams"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
    }

    @Test
    void updateTeam_withValidRequest_returns200() throws Exception {
        when(teamService.updateTeam(eq(teamId), any(), eq(actorId))).thenReturn(sampleTeam());

        mockMvc.perform(patch("/api/v1/teams/{id}", teamId)
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validUpdateRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(teamId.toString()));
    }

    @Test
    void updateTeam_whenNotFound_returns404() throws Exception {
        when(teamService.updateTeam(eq(teamId), any(), eq(actorId))).thenThrow(new TeamNotFoundException(teamId));

        mockMvc.perform(patch("/api/v1/teams/{id}", teamId)
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validUpdateRequest())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("TEAM_NOT_FOUND"));
    }

    @Test
    void updateStatus_withValidStatus_returns200() throws Exception {
        TeamStatusUpdateRequestDto request = new TeamStatusUpdateRequestDto();
        request.setStatus("ARCHIVED");
        when(teamService.updateTeamStatus(eq(teamId), any(), eq(actorId))).thenReturn(sampleTeam());

        mockMvc.perform(patch("/api/v1/teams/{id}/status", teamId)
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(teamId.toString()));
    }

    @Test
    void updateStatus_whenActorRoleIsEmployee_returns403WithoutCallingService() throws Exception {
        stubActorRole(UserRole.EMPLOYEE);
        TeamStatusUpdateRequestDto request = new TeamStatusUpdateRequestDto();
        request.setStatus("ARCHIVED");

        mockMvc.perform(patch("/api/v1/teams/{id}/status", teamId)
                        .header(ACTOR_HEADER, actorId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));

        verify(teamService, never()).updateTeamStatus(any(), any(), any());
    }

}
