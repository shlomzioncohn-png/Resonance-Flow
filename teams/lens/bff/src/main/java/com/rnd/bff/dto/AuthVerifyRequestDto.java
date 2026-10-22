package com.rnd.bff.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request DTO for BFF POST /internal/auth/verify
 * Gateway passes login credentials to BFF, which forwards to Inbox.
 * Login can be either email (for regular users) or username (for admin).
 */
public class AuthVerifyRequestDto {

    @NotBlank(message = "login is required")
    private String login;

    @NotBlank(message = "password is required")
    private String password;

    public AuthVerifyRequestDto() {
    }

    public AuthVerifyRequestDto(String login, String password) {
        this.login = login;
        this.password = password;
    }

    public String getLogin() {
        return login;
    }

    public void setLogin(String login) {
        this.login = login;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
