package com.rnd.inbox.auth.controller;
import com.rnd.inbox.auth.dto.AuthUserResponseDto;
import com.rnd.inbox.auth.dto.AuthVerifyRequestDto;
import com.rnd.inbox.auth.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Auth", description = "Verify user credentials")
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @Operation(summary = "Verify a username and password")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Credentials are valid",
                    content = @Content(schema = @Schema(implementation = AuthUserResponseDto.class))),
            @ApiResponse(responseCode = "400", description = "Login or password missing or too long"),
            @ApiResponse(responseCode = "401",
                    description = "Invalid login or password (same response for an unknown login, a wrong "
                            + "password, or an inactive user)"),
            @ApiResponse(responseCode = "429",
                    description = "Login temporarily locked after too many failed attempts")
    })
    @PostMapping("/verify")
    public AuthUserResponseDto verify(@Valid @RequestBody AuthVerifyRequestDto request) {
        return authService.verify(request);
    }
}
