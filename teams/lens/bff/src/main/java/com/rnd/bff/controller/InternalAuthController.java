package com.rnd.bff.controller;

import com.rnd.bff.dto.AuthRegisterRequestDto;
import com.rnd.bff.dto.AuthUserResponseDto;
import com.rnd.bff.dto.AuthVerifyRequestDto;
import com.rnd.bff.service.InternalAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Internal auth endpoints used by Gateway only.
 * These endpoints forward to Inbox via InboxUsersClient and map roles.
 * 
 * Not exposed to frontend - Gateway calls these endpoints to authenticate users.
 */
@Tag(name = "Internal Auth", description = "Internal authentication endpoints (Gateway only)")
@RestController
@RequestMapping("/internal/auth")
public class InternalAuthController {

    private final InternalAuthService internalAuthService;

    public InternalAuthController(InternalAuthService internalAuthService) {
        this.internalAuthService = internalAuthService;
    }

    /**
     * POST /internal/auth/verify
     * Verify user credentials and return user info with mapped role.
     * Called by Gateway during user login.
     */
    @Operation(summary = "Verify user credentials (internal endpoint)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Credentials verified",
                    content = @Content(schema = @Schema(implementation = AuthUserResponseDto.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request (missing login or password)"),
            @ApiResponse(responseCode = "401", description = "Invalid credentials"),
            @ApiResponse(responseCode = "503", description = "Inbox service unavailable")
    })
    @PostMapping("/verify")
    public ResponseEntity<AuthUserResponseDto> verify(
            @Valid @RequestBody AuthVerifyRequestDto request) {
        
        AuthUserResponseDto response = internalAuthService.verify(request);
        return ResponseEntity.ok(response);
    }
}
