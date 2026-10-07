package com.rnd.gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * One row of the admin "Users" table — intentionally narrow: only what the
 * list view needs. Inbox's `tenant` never appears here, and `role` is the
 * LENS role (MANAGER/EMPLOYEE), not Inbox's — see
 * com.rnd.bff.service.LensRoleMapper. Mirrors com.rnd.bff.dto.UserRowDTO.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserRowDTO {

    private String id;
    private String displayName;
    private String email;
    /** LENS role: MANAGER | EMPLOYEE — never Inbox's own role string. */
    private String role;
    /** Inbox's UserStatus: PENDING | ACTIVE | SUSPENDED | DISABLED. */
    private String status;
    private Instant lastLoginAt;
}
