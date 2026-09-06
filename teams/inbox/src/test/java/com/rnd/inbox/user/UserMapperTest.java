package com.rnd.inbox.user;

import com.rnd.inbox.tenant.Tenant;
import com.rnd.inbox.tenant.TenantSummaryDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UserMapperTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-01-01T00:00:00Z");

    private UserMapper userMapper;
    private Tenant tenant;

    @BeforeEach
    void setUp() {
        userMapper = new UserMapper(Clock.fixed(FIXED_NOW, ZoneOffset.UTC));

        tenant = new Tenant();
        tenant.setId(UUID.randomUUID());
        tenant.setName("Acme Inc");
        tenant.setSlug("acme");
    }

    private UserCreateRequestDto createRequest() {
        UserCreateRequestDto request = new UserCreateRequestDto();
        request.setTenantId(tenant.getId().toString());
        request.setDisplayName("Yehudit Cohen");
        request.setFirstName("Yehudit");
        request.setLastName("Cohen");
        request.setEmail("yehudit@acme.com");
        request.setPhone("0501234567");
        request.setUsername("yehudit");
        request.setRole("developer");
        return request;
    }

    @Test
    void toEntity_buildsUserFromCreateRequestAndTenant_withActiveStatus() {
        User user = userMapper.toEntity(createRequest(), tenant);

        assertThat(user.getTenant()).isSameAs(tenant);
        assertThat(user.getDisplayName()).isEqualTo("Yehudit Cohen");
        assertThat(user.getFirstName()).isEqualTo("Yehudit");
        assertThat(user.getLastName()).isEqualTo("Cohen");
        assertThat(user.getEmail()).isEqualTo("yehudit@acme.com");
        assertThat(user.getPhone()).isEqualTo("0501234567");
        assertThat(user.getUsername()).isEqualTo("yehudit");
        assertThat(user.getRole()).isEqualTo("developer");
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getCreatedAt()).isEqualTo(FIXED_NOW);
        assertThat(user.getUpdatedAt()).isEqualTo(FIXED_NOW);
    }

    @Test
    void applyUpdate_overwritesMutableFieldsAndBumpsUpdatedAt() {
        User user = new User();
        user.setTenant(tenant);
        user.setStatus(UserStatus.ACTIVE);
        user.setCreatedAt(Instant.parse("2025-01-01T00:00:00Z"));
        user.setUpdatedAt(Instant.parse("2025-01-01T00:00:00Z"));
        user.setDisplayName("Old Name");
        user.setEmail("old@acme.com");

        UserUpdateRequestDto request = new UserUpdateRequestDto();
        request.setDisplayName("New Name");
        request.setFirstName("New");
        request.setLastName("Name");
        request.setEmail("new@acme.com");
        request.setPhone("0509999999");
        request.setUsername("newname");
        request.setRole("manager");

        userMapper.applyUpdate(user, request);

        assertThat(user.getDisplayName()).isEqualTo("New Name");
        assertThat(user.getEmail()).isEqualTo("new@acme.com");
        assertThat(user.getUsername()).isEqualTo("newname");
        assertThat(user.getRole()).isEqualTo("manager");
        assertThat(user.getUpdatedAt()).isEqualTo(FIXED_NOW);

        // fields applyUpdate must never touch
        assertThat(user.getTenant()).isSameAs(tenant);
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getCreatedAt()).isEqualTo(Instant.parse("2025-01-01T00:00:00Z"));
    }

    @Test
    void applyUpdate_whenFieldIsOmitted_preservesItsExistingValue() {
        User user = new User();
        user.setDisplayName("Existing Name");
        user.setEmail("existing@acme.com");
        user.setUsername("existingusername");
        user.setPhone("0501111111");
        user.setRole("developer");

        UserUpdateRequestDto request = new UserUpdateRequestDto();
        request.setDisplayName("New Name");
        request.setRole("developer");
        // email, username and phone intentionally left null — not part of this update

        userMapper.applyUpdate(user, request);

        assertThat(user.getDisplayName()).isEqualTo("New Name");
        assertThat(user.getEmail()).isEqualTo("existing@acme.com");
        assertThat(user.getUsername()).isEqualTo("existingusername");
        assertThat(user.getPhone()).isEqualTo("0501111111");
    }

    @Test
    void applyStatusChange_setsStatusAndBumpsUpdatedAt() {
        User user = new User();
        user.setStatus(UserStatus.ACTIVE);
        user.setUpdatedAt(Instant.parse("2025-01-01T00:00:00Z"));

        userMapper.applyStatusChange(user, UserStatus.SUSPENDED);

        assertThat(user.getStatus()).isEqualTo(UserStatus.SUSPENDED);
        assertThat(user.getUpdatedAt()).isEqualTo(FIXED_NOW);
    }

    @Test
    void toResponseDto_mapsAllFieldsIncludingNestedTenantSummary() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setTenant(tenant);
        user.setDisplayName("Yehudit Cohen");
        user.setFirstName("Yehudit");
        user.setLastName("Cohen");
        user.setEmail("yehudit@acme.com");
        user.setPhone("0501234567");
        user.setUsername("yehudit");
        user.setRole("developer");
        user.setStatus(UserStatus.ACTIVE);
        user.setCreatedAt(FIXED_NOW);
        user.setUpdatedAt(FIXED_NOW);
        user.setLastLoginAt(FIXED_NOW);

        UserResponseDto dto = userMapper.toResponseDto(user);

        assertThat(dto.getId()).isEqualTo(user.getId());
        assertThat(dto.getDisplayName()).isEqualTo("Yehudit Cohen");
        assertThat(dto.getEmail()).isEqualTo("yehudit@acme.com");
        assertThat(dto.getRole()).isEqualTo("developer");
        assertThat(dto.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(dto.getCreatedAt()).isEqualTo(FIXED_NOW);
        assertThat(dto.getLastLoginAt()).isEqualTo(FIXED_NOW);

        TenantSummaryDto tenantSummary = dto.getTenant();
        assertThat(tenantSummary).isNotNull();
        assertThat(tenantSummary.getId()).isEqualTo(tenant.getId());
        assertThat(tenantSummary.getName()).isEqualTo("Acme Inc");
        assertThat(tenantSummary.getSlug()).isEqualTo("acme");
    }

    @Test
    void toResponseDto_whenTenantIsNull_tenantSummaryIsNull() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setTenant(null);
        user.setStatus(UserStatus.ACTIVE);

        UserResponseDto dto = userMapper.toResponseDto(user);

        assertThat(dto.getTenant()).isNull();
    }
}
