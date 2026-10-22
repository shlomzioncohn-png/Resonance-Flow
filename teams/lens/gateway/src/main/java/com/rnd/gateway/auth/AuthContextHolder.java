package com.rnd.gateway.auth;

/**
 * Thread-local holder for the JWT token during login request.
 * Allows AuthService to generate the token and AuthController to retrieve it
 * for setting the HttpOnly cookie, without passing it through method parameters.
 */
public class AuthContextHolder {
    private static final ThreadLocal<String> tokenHolder = new ThreadLocal<>();

    public static void setToken(String token) {
        tokenHolder.set(token);
    }

    public static String getToken() {
        return tokenHolder.get();
    }

    public static void clear() {
        tokenHolder.remove();
    }
}
