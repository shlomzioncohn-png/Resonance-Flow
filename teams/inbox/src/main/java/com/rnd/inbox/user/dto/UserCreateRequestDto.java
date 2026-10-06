package com.rnd.inbox.user.dto;
import com.rnd.inbox.user.model.UserRole;
import com.rnd.inbox.shared.messaging.validation.ValidEnum;
import com.rnd.inbox.shared.messaging.validation.ValidUuid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class UserCreateRequestDto {

    @NotBlank
    @ValidUuid
    private String tenantId;

    @NotBlank
    @Size(max = 255)
    private String displayName;

    @Size(max = 100)
    private String firstName;

    @Size(max = 100)
    private String lastName;

    @Email
    @Size(max = 255)
    private String email;

    @Size(max = 30)
    private String phone;

    @Size(max = 100)
    private String username;

    @NotBlank
    @ValidEnum(enumClass = UserRole.class)
    private String role;

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }
}
