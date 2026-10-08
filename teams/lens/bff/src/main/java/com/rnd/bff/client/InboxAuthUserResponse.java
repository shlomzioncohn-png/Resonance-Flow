package com.rnd.bff.client;

import java.util.UUID;

/**
 * Response DTO from Inbox POST /api/v1/auth/register and POST /api/v1/auth/verify
 * Contains user info needed by the BFF to map role and create JWT
 */
public class InboxAuthUserResponse {

    private UUID id;
    private String displayName;
    private String role;

    public InboxAuthUserResponse() {
    }

    public InboxAuthUserResponse(UUID id, String displayName, String role) {
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
