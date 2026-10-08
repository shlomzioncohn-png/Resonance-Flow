package com.rnd.gateway.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.UUID;

/**
 * Public authentication endpoints.
 * 
 * POST /auth/register - Create new user (limited to 10 registrations per hour per IP)
 * POST /auth/login - Login and get JWT token (limited to 5 failed attempts per minute per IP)
 * GET /auth/me - Get current user info (requires valid JWT)
 */
@Tag(name = "Auth", description = "Public authentication endpoints")
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;
    private final RateLimitingConfig.RateLimiter registerRateLimiter;
    private final RateLimitingConfig.RateLimiter loginRateLimiter;

    public AuthController(
            AuthService authService,
            RateLimitingConfig.RateLimiter registerRateLimiter,
            RateLimitingConfig.RateLimiter loginRateLimiter) {
        this.authService = authService;
        this.registerRateLimiter = registerRateLimiter;
        this.loginRateLimiter = loginRateLimiter;
    }

    /**
     * POST /auth/register
     * Register a new user with email, password, and display name.
     * Limited to 10 registrations per hour per IP.
     * Returns the user ID (no token yet - user must login to get one).
     */
    @Operation(summary = "Register a new user")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "User registered",
                    content = @Content(schema = @Schema(implementation = AuthRegisterResponseDto.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request (bad password, invalid email)"),
            @ApiResponse(responseCode = "409", description = "Email already registered"),
            @ApiResponse(responseCode = "429", description = "Too many registration attempts from this IP"),
            @ApiResponse(responseCode = "503", description = "Service unavailable")
    })
    @PostMapping("/register")
    public ResponseEntity<AuthRegisterResponseDto> register(
            @Valid @RequestBody AuthRegisterRequestDto request,
            HttpServletRequest httpRequest,
            UriComponentsBuilder uriBuilder) {
        
        String clientIp = getClientIp(httpRequest);
        
        // Check rate limit: 10 registrations per hour per IP
        if (!registerRateLimiter.allowRequest(clientIp)) {
            return ResponseEntity
                    .status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "3600")  // Retry after 1 hour
                    .build();
        }
        
        UUID userId = authService.register(request);
        
        AuthRegisterResponseDto response = new AuthRegisterResponseDto(userId);
        
        return ResponseEntity
                .created(uriBuilder.path("/users/{id}").build(userId))
                .body(response);
    }

    /**
     * POST /auth/login
     * Login with email (regular users) or username (admin).
     * Limited to 5 failed attempts per minute per IP.
     * Returns JWT token and user info.
     */
    @Operation(summary = "Login and get JWT token")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Login successful",
                    content = @Content(schema = @Schema(implementation = AuthLoginResponseDto.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request (missing login or password)"),
            @ApiResponse(responseCode = "401", description = "Invalid credentials"),
            @ApiResponse(responseCode = "429", description = "Too many failed login attempts from this IP"),
            @ApiResponse(responseCode = "503", description = "Service unavailable")
    })
    @PostMapping("/login")
    public ResponseEntity<AuthLoginResponseDto> login(
            @Valid @RequestBody AuthLoginRequestDto request,
            HttpServletRequest httpRequest) {
        
        String clientIp = getClientIp(httpRequest);
        
        // Check rate limit: 5 failed attempts per minute per IP
        // We track failed attempts only (see below)
        
        try {
            AuthLoginResponseDto response = authService.login(request);
            return ResponseEntity.ok(response);
        } catch (BffAuthClient.InvalidCredentialsException e) {
            // Track failed login attempt
            if (!loginRateLimiter.allowRequest(clientIp)) {
                // Too many failed attempts in the last minute
                return ResponseEntity
                        .status(HttpStatus.TOO_MANY_REQUESTS)
                        .header("Retry-After", "60")  // Retry after 1 minute
                        .build();
            }
            // Rethrow to be handled by GlobalExceptionHandler
            throw e;
        }
    }
    
    /**
     * Extract client IP from request.
     * Considers X-Forwarded-For header (for proxied requests).
     */
    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            // X-Forwarded-For can contain multiple IPs, take the first one
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * GET /auth/me
     * Get current authenticated user info from JWT token.
     * Does not require any service calls - just reads JWT claims.
     */
    @Operation(summary = "Get current user info")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "User info",
                    content = @Content(schema = @Schema(implementation = AuthMeResponseDto.class))),
            @ApiResponse(responseCode = "401", description = "Invalid or missing JWT token")
    })
    @GetMapping("/me")
    public ResponseEntity<AuthMeResponseDto> getCurrentUser(Authentication authentication) {
        // Check if user is authenticated
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        
        // Extract claims from JWT (already validated by Spring Security)
        // The principal is a Jwt object
        var jwt = (org.springframework.security.oauth2.jwt.Jwt) authentication.getPrincipal();
        
        UUID userId = UUID.fromString(jwt.getSubject());
        String displayName = jwt.getClaimAsString("name");
        String role = jwt.getClaimAsString("role");
        
        AuthMeResponseDto response = new AuthMeResponseDto(userId, displayName, role);
        return ResponseEntity.ok(response);
    }
}
