package com.rnd.gateway.auth;

/**
 * Request DTO sent to BFF POST /internal/auth/verify
 */
public class BffAuthVerifyRequest {

    private String login;
    private String password;

    public BffAuthVerifyRequest() {
    }

    public BffAuthVerifyRequest(String login, String password) {
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
