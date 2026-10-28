package com.rnd.gateway.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Service for JWT token generation and validation.
 * 
 * Generates RS256-signed JWTs for login responses.
 * Tokens contain:
 * - sub: Inbox user UUID
 * - role: MANAGER or EMPLOYEE (already mapped by BFF)
 * - name: User's display name
 * - iss: Issuer (lens-gateway)
 * - iat: Issued at timestamp
 * - exp: Expiration timestamp
 * 
 * Validation is handled by Spring Security's resource server configuration.
 */
@Service
public class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);
    /** The "iss" of every token the Gateway issues - JwtConfig accepts only this one. */
    public static final String ISSUER = "lens-gateway";

    private final JwtEncoder jwtEncoder;
    private final long tokenTtlSeconds;
    private final String keyId;

    public TokenService(
            JwtEncoder jwtEncoder,
            @Value("${lens.jwt.ttl-seconds:3600}") long tokenTtlSeconds,
            @Value("${lens.jwt.key-id:gateway-key-1}") String keyId) {
        this.jwtEncoder = jwtEncoder;
        this.tokenTtlSeconds = tokenTtlSeconds;
        this.keyId = keyId;
        log.info("TokenService initialized: TTL={} seconds, KeyID={}", tokenTtlSeconds, keyId);
    }

    /**
     * Generate a JWT token for a user.
     * 
     * @param userId Inbox user UUID
     * @param displayName User's display name
     * @param role MANAGER or EMPLOYEE (mapped from Inbox role by BFF)
     * @return JWT token string
     */
    public String generateToken(UUID userId, String displayName, String role) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(tokenTtlSeconds);

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim("role", role)
                .claim("name", displayName)
                .build();

        var jwtHeader = org.springframework.security.oauth2.jwt.JwsHeader.with(() -> "RS256")
                .keyId(keyId)
                .build();

        JwtEncoderParameters parameters = JwtEncoderParameters.from(jwtHeader, claims);
        String token = jwtEncoder.encode(parameters).getTokenValue();

        log.info("JWT token generated for user: {}, role: {}, expires: {}", userId, role, expiresAt);
        return token;
    }

    /**
     * Get the token TTL in seconds.
     * Used in login response to inform client of token expiration time.
     */
    public long getTokenTtlSeconds() {
        return tokenTtlSeconds;
    }
}
