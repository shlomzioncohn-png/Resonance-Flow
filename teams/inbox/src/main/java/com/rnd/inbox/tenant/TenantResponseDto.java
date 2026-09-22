package com.rnd.inbox.tenant;

import java.time.Instant;
import java.util.UUID;

public final class TenantResponseDto {

    private final UUID id;
    private final String name;
    private final String slug;
    private final TenantStatus status;
    private final String timezone;
    private final String localeDefault;
    private final Instant createdAt;
    private final Instant updatedAt;

    public TenantResponseDto(UUID id, String name, String slug, TenantStatus status, String timezone,
                              String localeDefault, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.name = name;
        this.slug = slug;
        this.status = status;
        this.timezone = timezone;
        this.localeDefault = localeDefault;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getSlug() {
        return slug;
    }

    public TenantStatus getStatus() {
        return status;
    }

    public String getTimezone() {
        return timezone;
    }

    public String getLocaleDefault() {
        return localeDefault;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
