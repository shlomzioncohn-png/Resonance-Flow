package com.rnd.gateway.auth;

import java.util.UUID;

/**
 * Response DTO for POST /auth/login endpoint
 * Contains JWT token and user info
 */
public class AuthLoginResponseDto {

    private String accessToken;
    private long expiresIn;  // Seconds until token expiry
    private UserInfo user;

    public AuthLoginResponseDto() {
    }

    public AuthLoginResponseDto(String accessToken, long expiresIn, UUID id, String displayName, String role) {
        this.accessToken = accessToken;
        this.expiresIn = expiresIn;
        this.user = new UserInfo(id, displayName, role);
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public long getExpiresIn() {
        return expiresIn;
    }

    public void setExpiresIn(long expiresIn) {
        this.expiresIn = expiresIn;
    }

    public UserInfo getUser() {
        return user;
    }

    public void setUser(UserInfo user) {
        this.user = user;
    }

    /**
     * Nested user info in the response
     */
    public static class UserInfo {
        private UUID id;
        private String displayName;
        private String role;

        public UserInfo() {
        }

        public UserInfo(UUID id, String displayName, String role) {
            this.id = id;
            this.displayName = displayName;
            this.role = role;
        }

        public UUID getId() {
            return id;
        }

        public void setId(UUID id) {
            this.id = id;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }
    }
}
