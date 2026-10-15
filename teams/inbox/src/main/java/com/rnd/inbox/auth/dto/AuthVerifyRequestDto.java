package com.rnd.inbox.auth.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class AuthVerifyRequestDto {

    @NotBlank
    @Size(max = 255)
    private String login;

    @NotBlank
    @Size(max = 72)
    private String password;

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
