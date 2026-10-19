package com.rnd.bff.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body of POST /manager/users, forwarded by Gateway's ManagerUsersController.
 * `role` is the LENS role (MANAGER/EMPLOYEE) — ManagerUsersService maps it to
 * Inbox's role. There is no tenantId here on purpose: the new user always
 * lands in the acting manager's own tenant, which BFF looks up itself.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserCreateRequestDTO {
    private String displayName;
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private String username;
    /** LENS role: MANAGER | EMPLOYEE. */
    private String role;
}
