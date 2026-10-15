package com.rnd.bff.client;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.UUID;

/**
 * Response DTO from Inbox POST /api/v1/auth/verify
 * Contains user info needed by the BFF to map role and create JWT.
 * 
 * Note: Inbox returns snake_case (display_name, tenant_id),
 * so @JsonNaming converts them to camelCase.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class InboxAuthUserResponse {

    private UUID id;
    private String displayName;
    private String role;
    private UUID tenantId;

    public InboxAuthUserResponse() {
    }

    public InboxAuthUserResponse(UUID id, String displayName, String role) {
        this.id = id;
        this.displayName = displayName;
        this.role = role;
    }

    public InboxAuthUserResponse(UUID id, String displayName, String role, UUID tenantId) {
        this.id = id;
        this.displayName = displayName;
        this.role = role;
        this.tenantId = tenantId;
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

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }
}
