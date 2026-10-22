package com.rnd.gateway.auth;

import java.util.UUID;

/**
 * Response DTO from BFF POST /internal/auth/register and POST /internal/auth/verify
 * Contains user info with role already mapped to LENS roles (MANAGER or EMPLOYEE)
 */
public class BffAuthUserResponse {

    private UUID id;
    private String displayName;
    private String role;  // MANAGER or EMPLOYEE

    public BffAuthUserResponse() {
    }

    public BffAuthUserResponse(UUID id, String displayName, String role) {
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
