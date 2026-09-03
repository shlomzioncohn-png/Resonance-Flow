package com.rnd.inbox.mapper;

import com.rnd.inbox.dto.OrganizationSummaryDto;
import com.rnd.inbox.dto.UserCreateRequestDto;
import com.rnd.inbox.dto.UserResponseDto;
import com.rnd.inbox.dto.UserUpdateRequestDto;
import com.rnd.inbox.model.entity.Organization;
import com.rnd.inbox.model.entity.User;
import com.rnd.inbox.model.enums.UserStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

@Component
public class UserMapper {

    private final Clock clock;

    public UserMapper(Clock clock) {
        this.clock = clock;
    }

    public User toEntity(UserCreateRequestDto dto, Organization organization) {
        User user = new User();
        user.setOrganization(organization);
        user.setDisplayName(dto.getDisplayName());
        user.setFirstName(dto.getFirstName());
        user.setLastName(dto.getLastName());
        user.setEmail(dto.getEmail());
        user.setPhone(dto.getPhone());
        user.setUsername(dto.getUsername());
        user.setRole(dto.getRole());
        user.setStatus(UserStatus.ACTIVE);
        Instant now = Instant.now(clock);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        return user;
    }

    public void applyUpdate(User user, UserUpdateRequestDto dto) {
        user.setDisplayName(dto.getDisplayName());
        user.setFirstName(dto.getFirstName());
        user.setLastName(dto.getLastName());
        user.setEmail(dto.getEmail());
        user.setPhone(dto.getPhone());
        user.setUsername(dto.getUsername());
        user.setRole(dto.getRole());
        user.setUpdatedAt(Instant.now(clock));
    }

    public void applyStatusChange(User user, UserStatus newStatus) {
        user.setStatus(newStatus);
        user.setUpdatedAt(Instant.now(clock));
    }

    public UserResponseDto toResponseDto(User user) {
        Organization organization = user.getOrganization();
        OrganizationSummaryDto organizationSummary = organization != null
                ? new OrganizationSummaryDto(organization.getId(), organization.getName(), organization.getSlug())
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
                organizationSummary,
                user.getCreatedAt(),
                user.getUpdatedAt(),
                user.getLastLoginAt());
    }
}
