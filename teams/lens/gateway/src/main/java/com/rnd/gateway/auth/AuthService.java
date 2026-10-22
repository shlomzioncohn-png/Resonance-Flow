package com.rnd.gateway.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Service for auth operations in the Gateway.
 * Orchestrates BFF calls and JWT token generation.
 * 
 * Note: Passwords are never logged or stored at the Gateway level.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final BffAuthClient bffAuthClient;
    private final TokenService tokenService;

    public AuthService(BffAuthClient bffAuthClient, TokenService tokenService) {
        this.bffAuthClient = bffAuthClient;
        this.tokenService = tokenService;
    }

    /**
     * Login user and generate JWT token.
     * Verifies credentials via BFF, then issues JWT.
     * 
     * The JWT token is returned ONLY via HttpOnly Set-Cookie header in the response.
     * The response body contains only user info and expiry time (no accessToken).
     * 
     * @param request containing login (email or username) and password
     * @return login response with user info and token expiry (token is in Set-Cookie header)
     */
    public AuthLoginResponseDto login(AuthLoginRequestDto request) {
        log.info("Login attempt for: {}", request.getLogin());
        
        // Verify credentials via BFF
        BffAuthUserResponse userResponse = bffAuthClient.verify(
                request.getLogin(),
                request.getPassword()
        );
        
        // Generate JWT token (will be returned in Set-Cookie header by AuthController)
        String token = tokenService.generateToken(
                userResponse.getId(),
                userResponse.getDisplayName(),
                userResponse.getRole()
        );
        
        long expiresIn = tokenService.getTokenTtlSeconds();
        
        // Store token in request scope for AuthController to set cookie
        // (We'll use a thread-local or request attribute pattern)
        AuthContextHolder.setToken(token);
        
        // Return only user info and expiry (no token in JSON body)
        AuthLoginResponseDto response = new AuthLoginResponseDto(
                expiresIn,
                userResponse.getId(),
                userResponse.getDisplayName(),
                userResponse.getRole()
        );
        
        log.info("User logged in: {}", userResponse.getId());
        return response;
    }
}
