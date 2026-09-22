package com.rnd.inbox.tenant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openapitools.jackson.nullable.JsonNullable;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TenantMapperTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-01-01T00:00:00Z");

    private TenantMapper tenantMapper;

    @BeforeEach
    void setUp() {
        tenantMapper = new TenantMapper(Clock.fixed(FIXED_NOW, ZoneOffset.UTC));
    }

    private TenantCreateRequestDto createRequest() {
        TenantCreateRequestDto request = new TenantCreateRequestDto();
        request.setName("Acme Inc");
        request.setSlug("acme");
        request.setTimezone("Asia/Jerusalem");
        request.setLocaleDefault("he-IL");
        return request;
    }

    @Test
    void toEntity_buildsTenantFromCreateRequest_withActiveStatus() {
        Tenant tenant = tenantMapper.toEntity(createRequest());

        assertThat(tenant.getName()).isEqualTo("Acme Inc");
        assertThat(tenant.getSlug()).isEqualTo("acme");
        assertThat(tenant.getTimezone()).isEqualTo("Asia/Jerusalem");
        assertThat(tenant.getLocaleDefault()).isEqualTo("he-IL");
        assertThat(tenant.getStatus()).isEqualTo(TenantStatus.ACTIVE);
        assertThat(tenant.getCreatedAt()).isEqualTo(FIXED_NOW);
        assertThat(tenant.getUpdatedAt()).isEqualTo(FIXED_NOW);
    }

    @Test
    void applyUpdate_overwritesMutableFieldsAndBumpsUpdatedAt() {
        Tenant tenant = new Tenant();
        tenant.setSlug("acme");
        tenant.setStatus(TenantStatus.ACTIVE);
        tenant.setCreatedAt(Instant.parse("2025-01-01T00:00:00Z"));
        tenant.setUpdatedAt(Instant.parse("2025-01-01T00:00:00Z"));
        tenant.setName("Old Name");
        tenant.setTimezone("UTC");

        TenantUpdateRequestDto request = new TenantUpdateRequestDto();
        request.setName("New Name");
        request.setTimezone(JsonNullable.of("Asia/Jerusalem"));
        request.setLocaleDefault(JsonNullable.of("he-IL"));

        tenantMapper.applyUpdate(tenant, request);

        assertThat(tenant.getName()).isEqualTo("New Name");
        assertThat(tenant.getTimezone()).isEqualTo("Asia/Jerusalem");
        assertThat(tenant.getLocaleDefault()).isEqualTo("he-IL");
        assertThat(tenant.getUpdatedAt()).isEqualTo(FIXED_NOW);

        // fields applyUpdate must never touch
        assertThat(tenant.getSlug()).isEqualTo("acme");
        assertThat(tenant.getStatus()).isEqualTo(TenantStatus.ACTIVE);
        assertThat(tenant.getCreatedAt()).isEqualTo(Instant.parse("2025-01-01T00:00:00Z"));
    }

    @Test
    void applyUpdate_whenFieldIsOmitted_preservesItsExistingValue() {
        Tenant tenant = new Tenant();
        tenant.setName("Existing Name");
        tenant.setTimezone("Asia/Jerusalem");
        tenant.setLocaleDefault("he-IL");

        TenantUpdateRequestDto request = new TenantUpdateRequestDto();
        request.setName("New Name");
        // timezone and localeDefault intentionally left undefined — not part of this update

        tenantMapper.applyUpdate(tenant, request);

        assertThat(tenant.getName()).isEqualTo("New Name");
        assertThat(tenant.getTimezone()).isEqualTo("Asia/Jerusalem");
        assertThat(tenant.getLocaleDefault()).isEqualTo("he-IL");
    }

    @Test
    void applyUpdate_whenFieldIsExplicitlyNull_clearsItRatherThanPreservingIt() {
        Tenant tenant = new Tenant();
        tenant.setTimezone("Asia/Jerusalem");
        tenant.setLocaleDefault("he-IL");

        TenantUpdateRequestDto request = new TenantUpdateRequestDto();
        request.setTimezone(JsonNullable.of(null));
        // localeDefault intentionally left undefined — not part of this update, must be preserved

        tenantMapper.applyUpdate(tenant, request);

        assertThat(tenant.getTimezone()).isNull();
        assertThat(tenant.getLocaleDefault()).isEqualTo("he-IL");
    }

    @Test
    void applyStatusChange_setsStatusAndBumpsUpdatedAt() {
        Tenant tenant = new Tenant();
        tenant.setStatus(TenantStatus.ACTIVE);
        tenant.setUpdatedAt(Instant.parse("2025-01-01T00:00:00Z"));

        tenantMapper.applyStatusChange(tenant, TenantStatus.SUSPENDED);

        assertThat(tenant.getStatus()).isEqualTo(TenantStatus.SUSPENDED);
        assertThat(tenant.getUpdatedAt()).isEqualTo(FIXED_NOW);
    }

    @Test
    void toResponseDto_mapsAllFields() {
        Tenant tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        tenant.setName("Acme Inc");
        tenant.setSlug("acme");
        tenant.setStatus(TenantStatus.ACTIVE);
        tenant.setTimezone("Asia/Jerusalem");
        tenant.setLocaleDefault("he-IL");
        tenant.setCreatedAt(FIXED_NOW);
        tenant.setUpdatedAt(FIXED_NOW);

        TenantResponseDto dto = tenantMapper.toResponseDto(tenant);

        assertThat(dto.getId()).isEqualTo(tenant.getId());
        assertThat(dto.getName()).isEqualTo("Acme Inc");
        assertThat(dto.getSlug()).isEqualTo("acme");
        assertThat(dto.getStatus()).isEqualTo(TenantStatus.ACTIVE);
        assertThat(dto.getTimezone()).isEqualTo("Asia/Jerusalem");
        assertThat(dto.getLocaleDefault()).isEqualTo("he-IL");
        assertThat(dto.getCreatedAt()).isEqualTo(FIXED_NOW);
        assertThat(dto.getUpdatedAt()).isEqualTo(FIXED_NOW);
    }
}
