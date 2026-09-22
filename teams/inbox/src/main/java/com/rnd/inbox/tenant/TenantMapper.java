package com.rnd.inbox.tenant;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

@Component
public class TenantMapper {

    private final Clock clock;

    public TenantMapper(Clock clock) {
        this.clock = clock;
    }

    public Tenant toEntity(TenantCreateRequestDto dto) {
        Tenant tenant = new Tenant();
        tenant.setName(dto.getName());
        tenant.setSlug(dto.getSlug());
        tenant.setTimezone(dto.getTimezone());
        tenant.setLocaleDefault(dto.getLocaleDefault());
        tenant.setStatus(TenantStatus.ACTIVE);
        Instant now = Instant.now(clock);
        tenant.setCreatedAt(now);
        tenant.setUpdatedAt(now);
        return tenant;
    }

    public void applyUpdate(Tenant tenant, TenantUpdateRequestDto dto) {
        if (dto.getName() != null) {
            tenant.setName(dto.getName());
        }
        if (dto.getTimezone().isPresent()) {
            tenant.setTimezone(dto.getTimezone().get());
        }
        if (dto.getLocaleDefault().isPresent()) {
            tenant.setLocaleDefault(dto.getLocaleDefault().get());
        }
        tenant.setUpdatedAt(Instant.now(clock));
    }

    public void applyStatusChange(Tenant tenant, TenantStatus newStatus) {
        tenant.setStatus(newStatus);
        tenant.setUpdatedAt(Instant.now(clock));
    }

    public TenantResponseDto toResponseDto(Tenant tenant) {
        return new TenantResponseDto(
                tenant.getId(),
                tenant.getName(),
                tenant.getSlug(),
                tenant.getStatus(),
                tenant.getTimezone(),
                tenant.getLocaleDefault(),
                tenant.getCreatedAt(),
                tenant.getUpdatedAt());
    }
}
