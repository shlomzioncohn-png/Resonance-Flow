package com.rnd.bff.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body of PATCH /manager/users/{id}/status, forwarded by Gateway's
 * ManagerUsersController. Re-validated here too (ManagerUsersService) —
 * belt and suspenders, same reasoning as the allowlist on the edit endpoint.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserStatusUpdateRequestDTO {
    private String status;
}
