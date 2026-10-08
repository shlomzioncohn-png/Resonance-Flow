package com.rnd.gateway.auth;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import java.util.Optional;
import java.util.UUID;

/**
 * Request-scoped component to hold current user information extracted from JWT.
 * Populated by HeaderInjectionFilter, used by BFF clients to add X-User-Id and X-User-Role headers.
 * 
 * If no JWT is present (e.g., on public endpoints), userId and role will be empty.
 */
@Component
@RequestScope
public class CurrentUserContext {
    
    private Optional<UUID> userId = Optional.empty();
    private Optional<String> role = Optional.empty();

    /**
     * Set current user from JWT.
     */
    public void setFromJwt(Jwt jwt) {
        if (jwt != null) {
            String subject = jwt.getSubject();
            if (subject != null) {
                try {
                    this.userId = Optional.of(UUID.fromString(subject));
                } catch (IllegalArgumentException e) {
                    // Invalid UUID format in subject claim
                }
            }
            String roleClaim = jwt.getClaimAsString("role");
            if (roleClaim != null) {
                this.role = Optional.of(roleClaim);
            }
        }
    }

    public Optional<UUID> getUserId() {
        return userId;
    }

    public Optional<String> getRole() {
        return role;
    }

    public void clear() {
        userId = Optional.empty();
        role = Optional.empty();
    }
}
