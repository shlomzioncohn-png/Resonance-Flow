package com.rnd.gateway.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body of POST /api/manager/users. Being a typed DTO is also the allowlist:
 * any other field the Frontend sends (tenantId, status, ...) is simply
 * never bound, so it can't leave the Gateway. Limits mirror Inbox's own
 * UserCreateRequestDto. `role` is the LENS role — BFF maps it to Inbox's,
 * and puts the user in the acting manager's own tenant.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserCreateRequestDTO {

    @NotBlank(message = "displayName is required")
    @Size(max = 255, message = "displayName must be at most 255 characters")
    private String displayName;

    @Size(max = 100, message = "firstName must be at most 100 characters")
    private String firstName;

    @Size(max = 100, message = "lastName must be at most 100 characters")
    private String lastName;

    @Email(message = "email must be a valid email address")
    @Size(max = 255, message = "email must be at most 255 characters")
    private String email;

    @Size(max = 30, message = "phone must be at most 30 characters")
    private String phone;

    @Size(max = 100, message = "username must be at most 100 characters")
    private String username;

    @NotBlank(message = "role is required")
    @Pattern(regexp = "MANAGER|EMPLOYEE", message = "role must be one of MANAGER, EMPLOYEE")
    private String role;
}
