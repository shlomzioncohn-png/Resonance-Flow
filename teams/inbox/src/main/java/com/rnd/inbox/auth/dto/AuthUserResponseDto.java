package com.rnd.inbox.auth.dto;
import com.rnd.inbox.user.model.UserRole;

import java.util.UUID;

public final class AuthUserResponseDto {

    private final UUID id;
    private final String displayName;
    private final UserRole role;
    private final UUID tenantId;

    public AuthUserResponseDto(UUID id, String displayName, UserRole role, UUID tenantId) {
        this.id = id;
        this.displayName = displayName;
        this.role = role;
        this.tenantId = tenantId;
    }

    public UUID getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public UserRole getRole() {
        return role;
    }

    public UUID getTenantId() {
        return tenantId;
    }
}
