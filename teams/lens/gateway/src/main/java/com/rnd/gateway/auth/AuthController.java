package com.rnd.gateway.auth;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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

@Tag(name = "Auth", description = "Public authentication endpoints")
@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;
    private final RateLimitingConfig.RateLimiter loginRateLimiter;

    public AuthController(
            AuthService authService,
            RateLimitingConfig.RateLimiter loginRateLimiter) {
        this.authService = authService;
        this.loginRateLimiter = loginRateLimiter;
    }

    /**
     * POST /auth/login
     * Login with email (regular users) or username (admin).
     * Limited to 5 failed attempts per minute per IP.
     * 
     * JWT token is returned ONLY via HttpOnly Set-Cookie header.
     * Response body contains user info and token expiry time.
     */
    @Operation(summary = "Login and get user info + token (sent via HttpOnly cookie)")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Login successful, token sent in Set-Cookie header",
                    content = @Content(schema = @Schema(implementation = AuthLoginResponseDto.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request (missing login or password)"),
            @ApiResponse(responseCode = "401", description = "Invalid credentials"),
            @ApiResponse(responseCode = "429", description = "Too many failed login attempts from this IP"),
            @ApiResponse(responseCode = "503", description = "Service unavailable")
    })
    @PostMapping("/login")
    public ResponseEntity<AuthLoginResponseDto> login(
            @Valid @RequestBody AuthLoginRequestDto request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        
        String clientIp = getClientIp(httpRequest);
        
        try {
            AuthLoginResponseDto response = authService.login(request);
            
            // Get the token from context (set by AuthService)
            String token = AuthContextHolder.getToken();
            AuthContextHolder.clear();
            
            if (token != null) {
                // Set HttpOnly cookie with JWT token
                String cookieValue = String.format(
                        "accessToken=%s; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=%d",
                        token,
                        response.getExpiresIn()
                );
                httpResponse.setHeader("Set-Cookie", cookieValue);
            }
            
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

    /**
     * POST /auth/logout
     * Logout current user by clearing the accessToken cookie.
     */
    @Operation(summary = "Logout (clear accessToken cookie)")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Logout successful"),
            @ApiResponse(responseCode = "401", description = "Not authenticated")
    })
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            Authentication authentication,
            HttpServletResponse httpResponse) {
        
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        
        // Clear the accessToken cookie by setting Max-Age to 0
        String clearCookie = "accessToken=; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=0";
        httpResponse.setHeader("Set-Cookie", clearCookie);
        
        return ResponseEntity.noContent().build();
    }
}
