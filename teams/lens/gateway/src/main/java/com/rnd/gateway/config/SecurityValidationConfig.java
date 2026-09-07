package com.rnd.gateway.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import jakarta.annotation.PostConstruct;

/**
 * Validates security configuration at startup.
 * 
 * Ensures that if dev-mode is disabled (production), the Gateway is configured
 * to use JWT-based authentication rather than hardcoded mock user IDs.
 * 
 * This prevents IDOR vulnerabilities where production deployments might accidentally
 * use mock credentials if the configuration is misconfigured.
 */
@Configuration
public class SecurityValidationConfig {

    private static final Logger logger = LoggerFactory.getLogger(SecurityValidationConfig.class);

    @Value("${app.dev-mode:false}")
    private boolean devMode;

    @Value("${app.jwt.issuer:#{null}}")
    private String jwtIssuer;

    @Value("${app.jwt.secret:#{null}}")
    private String jwtSecret;

    /**
     * Validates security configuration at startup.
     * 
     * Rules:
     * - If dev-mode is TRUE: mock authentication is allowed, JWT configuration is optional
     * - If dev-mode is FALSE (production):
     *   - JWT issuer and secret MUST be configured
     *   - Fail fast if they are missing to prevent IDOR vulnerabilities
     */
    @PostConstruct
    public void validateSecurityConfiguration() {
        if (devMode) {
            logger.warn("⚠️  DEV MODE ENABLED: Using mock user IDs. This is NOT suitable for production.");
            return;
        }

        // Production mode: ensure JWT is properly configured
        logger.info("Production mode enabled. Validating JWT configuration...");

        if (jwtIssuer == null || jwtIssuer.isBlank()) {
            String message = "SECURITY ERROR: Production mode (dev-mode=false) requires JWT issuer (app.jwt.issuer) to be configured. " +
                    "Without JWT validation, all requests would use hardcoded mock user IDs, creating an IDOR vulnerability. " +
                    "Set app.jwt.issuer or enable dev-mode for local testing.";
            logger.error(message);
            throw new IllegalStateException(message);
        }

        if (jwtSecret == null || jwtSecret.isBlank()) {
            String message = "SECURITY ERROR: Production mode (dev-mode=false) requires JWT secret (app.jwt.secret) to be configured. " +
                    "Without a secret, JWT tokens cannot be validated, creating an authentication bypass. " +
                    "Set app.jwt.secret or enable dev-mode for local testing.";
            logger.error(message);
            throw new IllegalStateException(message);
        }

        logger.info("✅ Security configuration validated: JWT issuer and secret are configured.");
    }
}
