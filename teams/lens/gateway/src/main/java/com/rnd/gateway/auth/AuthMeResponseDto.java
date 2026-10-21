package com.rnd.gateway.auth;

import java.util.UUID;

/**
 * Response DTO for GET /auth/me endpoint
 * Returns current user info from JWT claims (no database call needed)
 */
public class AuthMeResponseDto {

    private UUID id;
    private String displayName;
    private String role;

    public AuthMeResponseDto() {
    }

    public AuthMeResponseDto(UUID id, String displayName, String role) {
        this.id = id;
        this.displayName = displayName;
        this.role = role;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }
}
