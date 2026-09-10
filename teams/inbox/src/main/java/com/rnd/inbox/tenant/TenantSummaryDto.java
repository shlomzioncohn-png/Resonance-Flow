package com.rnd.inbox.tenant;

import java.util.UUID;

public final class TenantSummaryDto {

    private final UUID id;
    private final String name;
    private final String slug;

    public TenantSummaryDto(UUID id, String name, String slug) {
        this.id = id;
        this.name = name;
        this.slug = slug;
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
}
