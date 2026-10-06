package com.rnd.inbox.team.member;
import com.rnd.inbox.team.controller.TeamController;
import com.rnd.inbox.team.model.Team;
import com.rnd.inbox.user.model.User;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

// Separate from TeamController on purpose (CLAUDE.md §17: split a class doing two jobs) - this
// one owns only team_members, not Team's own CRUD. Same base path, since membership is a
// sub-resource of a specific team, not an independent resource.
@Tag(name = "Team Members", description = "Manage a team's membership")
@RestController
@RequestMapping("/api/v1/teams")
public class TeamMemberController {

    private final TeamMemberService teamMemberService;

    public TeamMemberController(TeamMemberService teamMemberService) {
        this.teamMemberService = teamMemberService;
    }

    @Operation(summary = "List a team's active members")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "List of active members",
                    content = @Content(schema = @Schema(implementation = TeamMemberResponseDto.class))),
            @ApiResponse(responseCode = "401", description = "Missing or unrecognized actor identity"),
            @ApiResponse(responseCode = "403", description = "Team is outside the actor's own tenant"),
            @ApiResponse(responseCode = "404", description = "Team not found")
    })
    @GetMapping("/{id}/members")
    public List<TeamMemberResponseDto> listMembers(@PathVariable UUID id, @CurrentActor UUID actorId) {
        return teamMemberService.listMembers(id, actorId);
    }

    @Operation(summary = "Add a member to a team (SYSTEM_ADMIN/TENANT_ADMIN only)")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Member added",
                    content = @Content(schema = @Schema(implementation = TeamMemberResponseDto.class))),
            @ApiResponse(responseCode = "401", description = "Missing or unrecognized actor identity"),
            @ApiResponse(responseCode = "403",
                    description = "Actor's role cannot manage team membership, or the user is outside the team's tenant"),
            @ApiResponse(responseCode = "404", description = "Team or user not found"),
            @ApiResponse(responseCode = "409", description = "User is already an active member of this team")
    })
    @PostMapping("/{id}/members")
    @RequireRole({UserRole.SYSTEM_ADMIN, UserRole.TENANT_ADMIN})
    public ResponseEntity<TeamMemberResponseDto> addMember(@PathVariable UUID id,
                                                            @Valid @RequestBody TeamMemberRequestDto request,
                                                            @CurrentActor UUID actorId) {
        TeamMemberResponseDto added = teamMemberService.addMember(id, request, actorId);
        return ResponseEntity.status(HttpStatus.CREATED).body(added);
    }

    @Operation(summary = "Remove a member from a team (SYSTEM_ADMIN/TENANT_ADMIN only) - "
            + "records a departure, does not delete the membership history")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Member removed"),
            @ApiResponse(responseCode = "401", description = "Missing or unrecognized actor identity"),
            @ApiResponse(responseCode = "403", description = "Actor's role cannot manage team membership"),
            @ApiResponse(responseCode = "404", description = "Team not found, or user is not an active member")
    })
    @DeleteMapping("/{id}/members/{userId}")
    @RequireRole({UserRole.SYSTEM_ADMIN, UserRole.TENANT_ADMIN})
    public ResponseEntity<Void> removeMember(@PathVariable UUID id, @PathVariable UUID userId,
                                              @CurrentActor UUID actorId) {
        teamMemberService.removeMember(id, userId, actorId);
        return ResponseEntity.noContent().build();
    }
}
