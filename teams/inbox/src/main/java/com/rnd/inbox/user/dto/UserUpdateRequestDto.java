package com.rnd.inbox.user.dto;
import com.rnd.inbox.user.model.UserRole;
import com.rnd.inbox.shared.messaging.validation.ValidEnum;
import com.rnd.inbox.validation.OptionalNotBlank;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import org.openapitools.jackson.nullable.JsonNullable;

public class UserUpdateRequestDto {

    @OptionalNotBlank
    @Size(max = 255)
    private String displayName;

    @Size(max = 100)
    private JsonNullable<String> firstName = JsonNullable.undefined();

    @Size(max = 100)
    private JsonNullable<String> lastName = JsonNullable.undefined();

    @Email
    @Size(max = 255)
    private JsonNullable<String> email = JsonNullable.undefined();

    @Size(max = 30)
    private JsonNullable<String> phone = JsonNullable.undefined();

    @Size(max = 100)
    private JsonNullable<String> username = JsonNullable.undefined();

    @OptionalNotBlank
    @ValidEnum(enumClass = UserRole.class)
    private String role;

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public JsonNullable<String> getFirstName() {
        return firstName;
    }

    public void setFirstName(JsonNullable<String> firstName) {
        this.firstName = firstName;
    }

    public JsonNullable<String> getLastName() {
        return lastName;
    }

    public void setLastName(JsonNullable<String> lastName) {
        this.lastName = lastName;
    }

    public JsonNullable<String> getEmail() {
        return email;
    }

    public void setEmail(JsonNullable<String> email) {
        this.email = email;
    }

    public JsonNullable<String> getPhone() {
        return phone;
    }

    public void setPhone(JsonNullable<String> phone) {
        this.phone = phone;
    }

    public JsonNullable<String> getUsername() {
        return username;
    }

    public void setUsername(JsonNullable<String> username) {
        this.username = username;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }
}
