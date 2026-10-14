package com.rnd.bff.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Everything UserRowDTO has, plus the fields only the edit dialog needs.
 * username/email/role/createdAt/updatedAt are read-only on the Frontend —
 * enforced by ManagerUsersController only accepting
 * displayName/firstName/lastName/phone on PATCH, not by this DTO's shape.
 * Mirrors com.rnd.gateway.dto.UserDetailsDTO field-for-field.
 *
 * Builder is named detailsBuilder() (not builder()) because Lombok's
 * @Builder doesn't pick up inherited fields automatically — this class
 * declares its own all-fields constructor (superclass fields passed via
 * super(...)) and puts @Builder on that constructor instead of the class.
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

    @Builder(builderMethodName = "detailsBuilder")
    public UserDetailsDTO(String id, String displayName, String email, String role, String status,
                           Instant lastLoginAt, boolean currentUser, String firstName, String lastName,
                           String phone, String username, Instant createdAt, Instant updatedAt) {
        super(id, displayName, email, role, status, lastLoginAt, currentUser);
        this.firstName = firstName;
        this.lastName = lastName;
        this.phone = phone;
        this.username = username;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }
}
