package com.rnd.inbox.user;

import com.rnd.inbox.tenant.Tenant;
import com.rnd.inbox.tenant.TenantSummaryDto;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

@Component
public class UserMapper {

    private final Clock clock;

    public UserMapper(Clock clock) {
        this.clock = clock;
    }

    public User toEntity(UserCreateRequestDto dto, Tenant tenant) {
        User user = new User();
        user.setTenant(tenant);
        user.setDisplayName(dto.getDisplayName());
        user.setFirstName(dto.getFirstName());
        user.setLastName(dto.getLastName());
        user.setEmail(dto.getEmail());
        user.setPhone(dto.getPhone());
        user.setUsername(dto.getUsername());
        user.setRole(UserRole.valueOf(dto.getRole()));
        user.setStatus(UserStatus.ACTIVE);
        Instant now = Instant.now(clock);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        return user;
    }

    public void applyUpdate(User user, UserUpdateRequestDto dto) {
        if (dto.getDisplayName() != null) {
            user.setDisplayName(dto.getDisplayName());
        }
        if (dto.getFirstName().isPresent()) {
            user.setFirstName(dto.getFirstName().get());
        }
        if (dto.getLastName().isPresent()) {
            user.setLastName(dto.getLastName().get());
        }
        if (dto.getEmail().isPresent()) {
            user.setEmail(dto.getEmail().get());
        }
        if (dto.getPhone().isPresent()) {
            user.setPhone(dto.getPhone().get());
        }
        if (dto.getUsername().isPresent()) {
            user.setUsername(dto.getUsername().get());
        }
        if (dto.getRole() != null) {
            user.setRole(UserRole.valueOf(dto.getRole()));
        }
        user.setUpdatedAt(Instant.now(clock));
    }

    public void applyStatusChange(User user, UserStatus newStatus) {
        user.setStatus(newStatus);
        user.setUpdatedAt(Instant.now(clock));
    }

    public UserResponseDto toResponseDto(User user) {
        Tenant tenant = user.getTenant();
        TenantSummaryDto tenantSummary = tenant != null
                ? new TenantSummaryDto(tenant.getId(), tenant.getName(), tenant.getSlug())
                : null;

        return new UserResponseDto(
                user.getId(),
                user.getDisplayName(),
                user.getFirstName(),
                user.getLastName(),
                user.getEmail(),
                user.getPhone(),
                user.getUsername(),
                user.getRole(),
                user.getStatus(),
                tenantSummary,
                user.getCreatedAt(),
                user.getUpdatedAt(),
                user.getLastLoginAt());
    }
}
