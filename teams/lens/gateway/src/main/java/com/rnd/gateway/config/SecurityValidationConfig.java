package com.rnd.gateway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import jakarta.annotation.PostConstruct;

/**
 * Validates security configuration at startup.
 *
 * Ensures the Gateway knows which Keycloak realm to trust (issuer) and where
 * to fetch its signing keys (JWKS). Without these, JWT validation can't work,
 * so it's better to fail fast at startup than to reject every request later.
 *
 * Also warns loudly while dev-mode is on, since WorkspaceController still
 * forwards a mock userId to BFF in that mode (replaced in Auth stage 2).
 */
@Configuration
public class SecurityValidationConfig {

    private static final Logger logger = LoggerFactory.getLogger(SecurityValidationConfig.class);

    @Value("${app.dev-mode:false}")
    private boolean devMode;

    @Value("${app.auth.enabled:false}")
    private boolean authEnabled;

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:#{null}}")
    private String jwtIssuerUri;

    @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:#{null}}")
    private String jwkSetUri;

    @PostConstruct
    public void validateSecurityConfiguration() {
        // In local dev mode with local JWT (not Keycloak), issuer-uri can be the default
        // This allows local testing with RS256 tokens signed by TokenService
        boolean usingLocalJwt = jwtIssuerUri.contains("8180");  // Keycloak default port
        boolean usingKeycloak = !usingLocalJwt;
        
        if (usingKeycloak) {
            if (jwtIssuerUri == null || jwtIssuerUri.isBlank()) {
                fail("JWT issuer (spring.security.oauth2.resourceserver.jwt.issuer-uri / KEYCLOAK_ISSUER_URI) " +
                        "must be configured. Without it, tokens from any issuer could not be rejected.");
            }

            if (jwkSetUri == null || jwkSetUri.isBlank()) {
                fail("JWKS URL (spring.security.oauth2.resourceserver.jwt.jwk-set-uri / KEYCLOAK_JWK_SET_URI) " +
                        "must be configured. Without it, token signatures cannot be verified.");
            }
        }

        if (!authEnabled) {
            logger.warn("⚠️  AUTH DISABLED (app.auth.enabled=false): /api/** accepts requests without a JWT.");
        }

        if (devMode) {
            logger.warn("⚠️  DEV MODE ENABLED: Using mock user IDs. This is NOT suitable for production.");
        }

        logger.info("✅ Security configuration validated: issuer={}, jwks={}, local-jwt={}", jwtIssuerUri, jwkSetUri, !usingKeycloak);
    }

    private void fail(String reason) {
        String message = "SECURITY ERROR: " + reason;
        logger.error(message);
        throw new IllegalStateException(message);
    }
}
