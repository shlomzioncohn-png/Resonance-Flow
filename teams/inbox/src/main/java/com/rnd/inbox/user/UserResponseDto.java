package com.rnd.inbox.user;

import com.rnd.inbox.tenant.TenantSummaryDto;


import java.time.Instant;
import java.util.UUID;

public final class UserResponseDto {

    private final UUID id;
    private final String displayName;
    private final String firstName;
    private final String lastName;
    private final String email;
    private final String phone;
    private final String username;
    private final String role;
    private final UserStatus status;
    private final TenantSummaryDto tenant;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final Instant lastLoginAt;

    public UserResponseDto(UUID id, String displayName, String firstName, String lastName, String email,
                            String phone, String username, String role, UserStatus status,
                            TenantSummaryDto tenant, Instant createdAt, Instant updatedAt,
                            Instant lastLoginAt) {
        this.id = id;
        this.displayName = displayName;
        this.firstName = firstName;
        this.lastName = lastName;
        this.email = email;
        this.phone = phone;
        this.username = username;
        this.role = role;
        this.status = status;
        this.tenant = tenant;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.lastLoginAt = lastLoginAt;
    }

    public UUID getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getFirstName() {
        return firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public String getEmail() {
        return email;
    }

    public String getPhone() {
        return phone;
    }

    public String getUsername() {
        return username;
    }

    public String getRole() {
        return role;
    }

    public UserStatus getStatus() {
        return status;
    }

    public TenantSummaryDto getTenant() {
        return tenant;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }
}
