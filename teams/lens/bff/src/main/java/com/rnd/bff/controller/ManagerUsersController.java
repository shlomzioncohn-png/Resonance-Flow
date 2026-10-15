package com.rnd.bff.controller;

import com.rnd.bff.dto.UserDetailsDTO;
import com.rnd.bff.dto.UserListResponseDTO;
import com.rnd.bff.dto.UserStatusUpdateRequestDTO;
import com.rnd.bff.service.ManagerUsersService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Admin "Users" screen (task spec: manager-users-screen-task.md).
 *
 * Gateway forwards /api/manager/users/** here as /manager/users/**, with
 * the caller's identity in X-User-Id and their LENS role in X-User-Role.
 * Role enforcement already happened in Gateway (hasRole("MANAGER")) — this
 * controller trusts that and doesn't re-check X-User-Role; it exists mainly
 * so the forwarded actor id is visible here for the self-status-change
 * check (ManagerUsersService) and for the X-Actor-Id Inbox eventually sees.
 */
@RestController
@RequestMapping("/manager/users")
@Tag(name = "Manager Users", description = "Called only by the Gateway, never directly by the Frontend")
public class ManagerUsersController {

    private static final Logger logger = LoggerFactory.getLogger(ManagerUsersController.class);

    private final ManagerUsersService managerUsersService;

    public ManagerUsersController(ManagerUsersService managerUsersService) {
        this.managerUsersService = managerUsersService;
    }

    @Operation(summary = "List users", description = "Paginated users list, from Inbox's canonical users table.")
    @GetMapping
    public ResponseEntity<UserListResponseDTO> listUsers(
            @Parameter(description = "0-based page index") @RequestParam(required = false) Integer page,
            @Parameter(description = "Page size") @RequestParam(required = false) Integer size,
            @Parameter(description = "e.g. displayName,asc") @RequestParam(required = false) String sort,
            @RequestHeader("X-User-Id") String userId) {

        logger.info("Users list request - actorId: {}, page: {}, size: {}, sort: {}", userId, page, size, sort);
        return ResponseEntity.ok(managerUsersService.listUsers(userId, page, size, sort));
    }

    @Operation(summary = "Get user details", description = "404 if the user id doesn't exist.")
    @GetMapping("/{id}")
    public ResponseEntity<UserDetailsDTO> getUser(
            @PathVariable String id,
            @RequestHeader("X-User-Id") String userId) {

        logger.info("User details request - actorId: {}, userId: {}", userId, id);
        return ResponseEntity.ok(managerUsersService.getUser(userId, id));
    }

    @Operation(summary = "Edit a user", description = "Only displayName/firstName/lastName/phone are ever forwarded to Inbox, regardless of what's in the body.")
    @PatchMapping("/{id}")
    public ResponseEntity<UserDetailsDTO> updateUser(
            @PathVariable String id,
            @RequestBody Map<String, Object> body,
            @RequestHeader("X-User-Id") String userId) {

        logger.info("User edit request - actorId: {}, userId: {}", userId, id);
        return ResponseEntity.ok(managerUsersService.updateUser(userId, id, body));
    }

    @Operation(summary = "Change a user's status", description = "ACTIVE | SUSPENDED | DISABLED. 409 if the actor targets their own id.")
    @PatchMapping("/{id}/status")
    public ResponseEntity<UserDetailsDTO> updateStatus(
            @PathVariable String id,
            @RequestBody UserStatusUpdateRequestDTO body,
            @RequestHeader("X-User-Id") String userId) {

        logger.info("User status change request - actorId: {}, userId: {}, newStatus: {}", userId, id, body.getStatus());
        return ResponseEntity.ok(managerUsersService.updateStatus(userId, id, body.getStatus()));
    }
}
