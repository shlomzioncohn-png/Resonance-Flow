package com.rnd.gateway.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * Request DTO for public POST /auth/login endpoint
 * Login can be email (for regular users) or username (for admin)
 */
public class AuthLoginRequestDto {

    @NotBlank(message = "login is required")
    private String login;

    @NotBlank(message = "password is required")
    private String password;

    public AuthLoginRequestDto() {
    }

    public AuthLoginRequestDto(String login, String password) {
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
