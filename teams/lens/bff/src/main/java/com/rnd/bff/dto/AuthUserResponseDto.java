package com.rnd.bff.dto;

import java.util.UUID;

/**
 * Response DTO from BFF auth endpoints (/internal/auth/register and /internal/auth/verify).
 * Contains user info with role already mapped from Inbox role to LENS role.
 * Used by Gateway to create JWT and return to client.
 */
public class AuthUserResponseDto {

    private UUID id;
    private String displayName;
    private String role;  // MANAGER or EMPLOYEE (mapped from Inbox roles)

    public AuthUserResponseDto() {
    }

    public AuthUserResponseDto(UUID id, String displayName, String role) {
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
