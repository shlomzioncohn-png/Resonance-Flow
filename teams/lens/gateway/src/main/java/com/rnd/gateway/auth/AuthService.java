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
     * Register a new user.
     * Forwards request to BFF, which forwards to Inbox.
     * 
     * @param request containing email, password, displayName
     * @return user ID (no token returned on registration)
     */
    public UUID register(AuthRegisterRequestDto request) {
        log.info("Registering user: {}", request.getEmail());
        
        BffAuthUserResponse userResponse = bffAuthClient.register(
                request.getEmail(),
                request.getPassword(),
                request.getDisplayName()
        );
        
        return userResponse.getId();
    }

    /**
     * Login user and generate JWT token.
     * Verifies credentials via BFF, then issues JWT.
     * 
     * @param request containing login (email or username) and password
     * @return login response with JWT token
     */
    public AuthLoginResponseDto login(AuthLoginRequestDto request) {
        log.info("Login attempt for: {}", request.getLogin());
        
        // Verify credentials via BFF
        BffAuthUserResponse userResponse = bffAuthClient.verify(
                request.getLogin(),
                request.getPassword()
        );
        
        // Generate JWT token
        String token = tokenService.generateToken(
                userResponse.getId(),
                userResponse.getDisplayName(),
                userResponse.getRole()
        );
        
        long expiresIn = tokenService.getTokenTtlSeconds();
        
        AuthLoginResponseDto response = new AuthLoginResponseDto(
                token,
                expiresIn,
                userResponse.getId(),
                userResponse.getDisplayName(),
                userResponse.getRole()
        );
        
        log.info("User logged in: {}", userResponse.getId());
        return response;
    }
}
