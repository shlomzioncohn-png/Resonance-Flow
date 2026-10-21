package com.rnd.bff.client;

/**
 * Request DTO sent to Inbox POST /api/v1/auth/verify
 */
public class InboxAuthVerifyRequest {

    private String login;
    private String password;

    public InboxAuthVerifyRequest() {
    }

    public InboxAuthVerifyRequest(String login, String password) {
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
