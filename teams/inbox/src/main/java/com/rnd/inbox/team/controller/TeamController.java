package com.rnd.inbox.team.controller;
import com.rnd.inbox.team.dto.TeamCreateRequestDto;
import com.rnd.inbox.team.dto.TeamResponseDto;
import com.rnd.inbox.team.dto.TeamStatusUpdateRequestDto;
import com.rnd.inbox.team.dto.TeamUpdateRequestDto;
import com.rnd.inbox.team.member.TeamMemberController;
import com.rnd.inbox.team.model.Team;
import com.rnd.inbox.team.service.TeamService;
import com.rnd.inbox.tenant.model.Tenant;
import com.rnd.inbox.shared.dto.PageResponseDto;
import com.rnd.inbox.shared.security.CurrentActor;
import com.rnd.inbox.shared.security.RequireRole;
import com.rnd.inbox.user.model.UserRole;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.UUID;

// Team membership endpoints (list/add/remove members) live in TeamMemberController, not here -
// this class stays focused on Team's own CRUD, per CLAUDE.md §17 (split a class doing two jobs).
@Tag(name = "Teams", description = "Create, update, and look up teams")
@RestController
@RequestMapping("/api/v1/teams")
public class TeamController {

    private final TeamService teamService;

    public TeamController(TeamService teamService) {
        this.teamService = teamService;
    }

    @Operation(summary = "Create a new team")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Team created",
                    content = @Content(schema = @Schema(implementation = TeamResponseDto.class))),
            @ApiResponse(responseCode = "401", description = "Missing or unrecognized actor identity"),
            @ApiResponse(responseCode = "403",
                    description = "Actor's role cannot create teams (SYSTEM_ADMIN/TENANT_ADMIN only), "
                            + "or the tenant/team lead is outside the actor's own tenant"),
            @ApiResponse(responseCode = "404", description = "Tenant or team lead not found")
    })
    @PostMapping
    @RequireRole({UserRole.SYSTEM_ADMIN, UserRole.TENANT_ADMIN})
    public ResponseEntity<TeamResponseDto> createTeam(@Valid @RequestBody TeamCreateRequestDto request,
                                                       @CurrentActor UUID actorId,
                                                       UriComponentsBuilder uriBuilder) {
        TeamResponseDto created = teamService.createTeam(request, actorId);
        return ResponseEntity
                .created(uriBuilder.path("/api/v1/teams/{id}").build(created.getId()))
                .body(created);
    }

    @Operation(summary = "Get a team by id")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Team found",
                    content = @Content(schema = @Schema(implementation = TeamResponseDto.class))),
            @ApiResponse(responseCode = "401", description = "Missing or unrecognized actor identity"),
            @ApiResponse(responseCode = "403",
                    description = "Team is outside the actor's scope (SYSTEM_ADMIN: any tenant; everyone else: their own tenant only)"),
            @ApiResponse(responseCode = "404", description = "Team not found")
    })
    @GetMapping("/{id}")
    public TeamResponseDto getTeamById(@PathVariable UUID id, @CurrentActor UUID actorId) {
        return teamService.getTeamById(id, actorId);
    }

    @Operation(summary = "List teams within the actor's scope, paginated "
            + "(SYSTEM_ADMIN: every tenant; everyone else: their own tenant only)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of teams",
                    content = @Content(schema = @Schema(implementation = PageResponseDto.class))),
            @ApiResponse(responseCode = "401", description = "Missing or unrecognized actor identity")
    })
    @GetMapping
    public PageResponseDto<TeamResponseDto> listTeams(Pageable pageable, @CurrentActor UUID actorId) {
        return teamService.listTeams(pageable, actorId);
    }

    @Operation(summary = "Update a team's details")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Team updated",
                    content = @Content(schema = @Schema(implementation = TeamResponseDto.class))),
            @ApiResponse(responseCode = "401", description = "Missing or unrecognized actor identity"),
            @ApiResponse(responseCode = "403", description = "Team or team lead is outside the actor's own tenant"),
            @ApiResponse(responseCode = "404", description = "Team or team lead not found")
    })
    @PatchMapping("/{id}")
    public TeamResponseDto updateTeam(@PathVariable UUID id, @Valid @RequestBody TeamUpdateRequestDto request,
                                       @CurrentActor UUID actorId) {
        return teamService.updateTeam(id, request, actorId);
    }

    @Operation(summary = "Change a team's status (SYSTEM_ADMIN/TENANT_ADMIN only)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Status updated",
                    content = @Content(schema = @Schema(implementation = TeamResponseDto.class))),
            @ApiResponse(responseCode = "401", description = "Missing or unrecognized actor identity"),
            @ApiResponse(responseCode = "403", description = "Actor's role cannot change team status"),
            @ApiResponse(responseCode = "404", description = "Team not found")
    })
    @PatchMapping("/{id}/status")
    @RequireRole({UserRole.SYSTEM_ADMIN, UserRole.TENANT_ADMIN})
    public TeamResponseDto updateStatus(@PathVariable UUID id,
                                         @Valid @RequestBody TeamStatusUpdateRequestDto request,
                                         @CurrentActor UUID actorId) {
        return teamService.updateTeamStatus(id, request, actorId);
    }
}
