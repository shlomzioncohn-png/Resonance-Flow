package com.rnd.inbox.user;

import com.rnd.inbox.shared.dto.PageResponseDto;
import com.rnd.inbox.shared.security.CurrentActor;
import com.rnd.inbox.shared.security.RequireRole;
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

@Tag(name = "Users", description = "Create, update, and look up users, including their tenant")
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @Operation(summary = "Create a new user")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "User created",
                    content = @Content(schema = @Schema(implementation = UserResponseDto.class))),
            @ApiResponse(responseCode = "401", description = "Missing or unrecognized actor identity"),
            @ApiResponse(responseCode = "403",
                    description = "Actor's role cannot create users, or not in this tenant "
                            + "(SYSTEM_ADMIN: any tenant; TENANT_ADMIN: their own tenant only; "
                            + "TEAM_LEAD/EMPLOYEE: cannot create users)"),
            @ApiResponse(responseCode = "404", description = "Tenant not found"),
            @ApiResponse(responseCode = "409", description = "Email or username already exists")
    })
    @PostMapping
    @RequireRole({UserRole.SYSTEM_ADMIN, UserRole.TENANT_ADMIN})
    public ResponseEntity<UserResponseDto> createUser(@Valid @RequestBody UserCreateRequestDto request,
                                                        @CurrentActor UUID actorId,
                                                        UriComponentsBuilder uriBuilder) {
        UserResponseDto created = userService.createUser(request, actorId);
        return ResponseEntity
                .created(uriBuilder.path("/api/v1/users/{id}").build(created.getId()))
                .body(created);
    }

    @Operation(summary = "Get a user by id, including its tenant")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "User found",
                    content = @Content(schema = @Schema(implementation = UserResponseDto.class))),
            @ApiResponse(responseCode = "401", description = "Missing or unrecognized actor identity"),
            @ApiResponse(responseCode = "403",
                    description = "User is outside the actor's scope "
                            + "(SYSTEM_ADMIN: everyone; TENANT_ADMIN: their tenant; "
                            + "TEAM_LEAD: their teams; EMPLOYEE: themselves only)"),
            @ApiResponse(responseCode = "404", description = "User not found")
    })
    @GetMapping("/{id}")
    public UserResponseDto getUserById(@PathVariable UUID id, @CurrentActor UUID actorId) {
        return userService.getUserById(id, actorId);
    }

    @Operation(summary = "List users within the actor's scope, paginated "
            + "(SYSTEM_ADMIN: everyone; TENANT_ADMIN: their tenant; TEAM_LEAD: their teams; EMPLOYEE: themselves)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Page of users",
                    content = @Content(schema = @Schema(implementation = PageResponseDto.class))),
            @ApiResponse(responseCode = "401", description = "Missing or unrecognized actor identity")
    })
    @GetMapping
    public PageResponseDto<UserResponseDto> listUsers(Pageable pageable, @CurrentActor UUID actorId) {
        return userService.listUsers(pageable, actorId);
    }

    @Operation(summary = "Update a user's details")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "User updated",
                    content = @Content(schema = @Schema(implementation = UserResponseDto.class))),
            @ApiResponse(responseCode = "401", description = "Missing or unrecognized actor identity"),
            @ApiResponse(responseCode = "403",
                    description = "User is outside the actor's scope "
                            + "(SYSTEM_ADMIN: everyone; TENANT_ADMIN: their tenant; "
                            + "TEAM_LEAD: their teams; EMPLOYEE: themselves only)"),
            @ApiResponse(responseCode = "404", description = "User not found"),
            @ApiResponse(responseCode = "409", description = "Email or username already exists")
    })
    @PatchMapping("/{id}")
    public UserResponseDto updateUser(@PathVariable UUID id, @Valid @RequestBody UserUpdateRequestDto request,
                                       @CurrentActor UUID actorId) {
        return userService.updateUser(id, request, actorId);
    }

    @Operation(summary = "Change a user's status")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Status updated",
                    content = @Content(schema = @Schema(implementation = UserResponseDto.class))),
            @ApiResponse(responseCode = "401", description = "Missing or unrecognized actor identity"),
            @ApiResponse(responseCode = "403",
                    description = "User is outside the actor's scope "
                            + "(SYSTEM_ADMIN: everyone; TENANT_ADMIN: their tenant; "
                            + "TEAM_LEAD: their teams; EMPLOYEE: themselves only)"),
            @ApiResponse(responseCode = "404", description = "User not found")
    })
    @PatchMapping("/{id}/status")
    public UserResponseDto updateStatus(@PathVariable UUID id,
                                         @Valid @RequestBody UserStatusUpdateRequestDto request,
                                         @CurrentActor UUID actorId) {
        return userService.updateStatus(id, request, actorId);
    }
}
