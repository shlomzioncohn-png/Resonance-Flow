package com.rnd.gateway.auth;

import java.util.UUID;

/**
 * Response DTO for POST /auth/register endpoint
 * Returns the user ID. User must login separately to get a JWT token.
 */
public class AuthRegisterResponseDto {

    private UUID userId;

    public AuthRegisterResponseDto() {
    }

    public AuthRegisterResponseDto(UUID userId) {
        this.userId = userId;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }
}
