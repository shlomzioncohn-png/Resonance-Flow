package com.rnd.gateway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Everything UserRowDTO has, plus the fields only the edit dialog needs.
 * username/email/role/createdAt/updatedAt are read-only on the Frontend —
 * enforced by PATCH /api/manager/users/{id} only accepting
 * displayName/firstName/lastName/phone (see ManagerUsersController), not by
 * this DTO's shape. Mirrors com.rnd.bff.dto.UserDetailsDTO.
 *
 * The Gateway only ever deserializes this from BFF's response body (Jackson
 * uses the no-arg constructor + setters below), never constructs one itself
 * — no builder needed here, unlike the BFF's own copy.
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UserDetailsDTO extends UserRowDTO {

    private String firstName;
    private String lastName;
    private String phone;
    private String username;
    private Instant createdAt;
    private Instant updatedAt;
}
