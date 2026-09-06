package com.rnd.inbox.controller;

import com.rnd.inbox.dto.PageResponseDto;
import com.rnd.inbox.dto.UserCreateRequestDto;
import com.rnd.inbox.dto.UserResponseDto;
import com.rnd.inbox.dto.UserStatusUpdateRequestDto;
import com.rnd.inbox.dto.UserUpdateRequestDto;
import com.rnd.inbox.security.CurrentActor;
import com.rnd.inbox.service.UserService;
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

@Tag(name = "Users", description = "Create, update, and look up users, including their organization")
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
            @ApiResponse(responseCode = "403", description = "Actor cannot create a user in another organization"),
            @ApiResponse(responseCode = "404", description = "Organization not found"),
            @ApiResponse(responseCode = "409", description = "Email or username already exists")
    })
    @PostMapping
    public ResponseEntity<UserResponseDto> createUser(@Valid @RequestBody UserCreateRequestDto request,
                                                        @CurrentActor UUID actorId,
                                                        UriComponentsBuilder uriBuilder) {
        UserResponseDto created = userService.createUser(request, actorId);
        return ResponseEntity
                .created(uriBuilder.path("/api/v1/users/{id}").build(created.getId()))
                .body(created);
    }

    @Operation(summary = "Get a user by id, including its organization")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "User found",
                    content = @Content(schema = @Schema(implementation = UserResponseDto.class))),
            @ApiResponse(responseCode = "404", description = "User not found")
    })
    @GetMapping("/{id}")
    public UserResponseDto getUserById(@PathVariable UUID id) {
        return userService.getUserById(id);
    }

    @Operation(summary = "List users, paginated")
    @ApiResponse(responseCode = "200", description = "Page of users",
            content = @Content(schema = @Schema(implementation = PageResponseDto.class)))
    @GetMapping
    public PageResponseDto<UserResponseDto> listUsers(Pageable pageable) {
        return userService.listUsers(pageable);
    }

    @Operation(summary = "Update a user's details")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "User updated",
                    content = @Content(schema = @Schema(implementation = UserResponseDto.class))),
            @ApiResponse(responseCode = "401", description = "Missing or unrecognized actor identity"),
            @ApiResponse(responseCode = "403", description = "Actor cannot manage a user outside their organization"),
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
            @ApiResponse(responseCode = "403", description = "Actor cannot manage a user outside their organization"),
            @ApiResponse(responseCode = "404", description = "User not found")
    })
    @PatchMapping("/{id}/status")
    public UserResponseDto updateStatus(@PathVariable UUID id,
                                         @Valid @RequestBody UserStatusUpdateRequestDto request,
                                         @CurrentActor UUID actorId) {
        return userService.updateStatus(id, request, actorId);
    }
}
