package com.rnd.bff.client;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Wire shape of one entry in Inbox's {@code GET /api/v1/users} response
 * (Inbox's {@code UserResponseDto} — teams/inbox/src/main/java/com/rnd/inbox/user/UserResponseDto.java),
 * used only to deserialize InboxUserClient's RestTemplate call. Field names
 * mirror Inbox's JSON 1:1, including the nested tenant summary — this is
 * never returned to the Frontend as-is, UserService maps it to UserDTO
 * (the BFF's own contract) at the boundary, same split as
 * InboxTaskRecord/TaskDTO. Inbox writes JSON in snake_case (display_name,
 * last_login_at, ...), hence @JsonNaming — same as InboxTaskResponse.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class InboxUserRecord {

    private String id;
    private String displayName;
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private String username;
    private String role;
    /** One of Inbox's UserStatus values: PENDING, ACTIVE, SUSPENDED, DISABLED. */
    private String status;
    private TenantSummary tenant;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant lastLoginAt;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class TenantSummary {
        private String id;
        private String name;
        private String slug;
    }
}
