package com.rnd.gateway.auth;

import java.util.UUID;

/**
 * Response DTO for POST /auth/login endpoint
 * Contains user info and token expiry time.
 * 
 * NOTE: JWT token is sent ONLY via HttpOnly cookie (Set-Cookie header),
 * NOT in the response body. This prevents XSS attacks from stealing the token
 * via JavaScript access to response.accessToken.
 * 
 * Frontend should NOT attempt to extract or store the token — the browser
 * handles cookie management automatically.
 */
public class AuthLoginResponseDto {

    private long expiresIn;  // Seconds until token expiry
    private UserInfo user;

    public AuthLoginResponseDto() {
    }

    public AuthLoginResponseDto(long expiresIn, UUID id, String displayName, String role) {
        this.expiresIn = expiresIn;
        this.user = new UserInfo(id, displayName, role);
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
