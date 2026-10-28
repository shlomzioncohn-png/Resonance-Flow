package com.rnd.gateway.config;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.rnd.gateway.auth.TokenService;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * JWT configuration for Gateway.
 * 
 * Generates RS256-signed tokens for login.
 * Validates incoming JWTs from authenticated requests.
 * 
 * RSA key pair must exist at configured paths:
 * - lens.jwt.private-key-location: Private key for signing (PKCS8 format)
 * - lens.jwt.public-key-location: Public key for validation (X509 format)
 * 
 * For local development, generate keys with:
 *   openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out jwt-private.pem
 *   openssl rsa -in jwt-private.pem -pubout -out jwt-public.pem
 */
@Configuration
public class JwtConfig {

    private static final Logger log = LoggerFactory.getLogger(JwtConfig.class);

    @Value("${lens.jwt.private-key-location}")
    private String privateKeyLocation;

    @Value("${lens.jwt.public-key-location}")
    private String publicKeyLocation;

    /**
     * Bean for encoding (signing) JWTs.
     * Used by TokenService to generate tokens for login responses.
     */
    @Bean
    public JwtEncoder jwtEncoder() throws Exception {
        PrivateKey privateKey = loadPrivateKey(privateKeyLocation);
        PublicKey publicKey = loadPublicKey(publicKeyLocation);

        RSAKey rsaKey = new RSAKey.Builder((java.security.interfaces.RSAPublicKey) publicKey)
                .privateKey((java.security.interfaces.RSAPrivateKey) privateKey)
                .keyID("gateway-key-1")
                .build();

        JWKSource<SecurityContext> jwkSource = new ImmutableJWKSet<>(
                new com.nimbusds.jose.jwk.JWKSet(rsaKey));

        log.info("JWT encoder initialized with private key from: {}", privateKeyLocation);
        return new NimbusJwtEncoder(jwkSource);
    }

    /**
     * Bean for decoding (validating) JWTs.
     * Used by Spring Security to validate incoming request tokens.
     */
    @Bean
    public JwtDecoder jwtDecoder() throws Exception {
        PublicKey publicKey = loadPublicKey(publicKeyLocation);

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(
                (java.security.interfaces.RSAPublicKey) publicKey)
                .build();
        // Expiry (the default) plus the issuer: only tokens this Gateway issued
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(TokenService.ISSUER));

        log.info("JWT decoder initialized with public key from: {}", publicKeyLocation);
        return decoder;
    }

    /**
     * Load private key from PEM file (PKCS8 format).
     */
    private PrivateKey loadPrivateKey(String keyPath) throws Exception {
        if (!new File(keyPath).exists()) {
            throw new IllegalStateException(
                    "Private key file not found at: " + keyPath +
                    ". Generate it with: openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out " + keyPath);
        }

        String key = new String(Files.readAllBytes(Paths.get(keyPath)))
                .replaceAll("-----BEGIN.*-----", "")
                .replaceAll("-----END.*-----", "")
                .replaceAll("\\s", "");

        byte[] decodedKey = Base64.getDecoder().decode(key);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(decodedKey);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        return keyFactory.generatePrivate(spec);
    }

    /**
     * Load public key from PEM file (X509 format).
     */
    private PublicKey loadPublicKey(String keyPath) throws Exception {
        if (!new File(keyPath).exists()) {
            throw new IllegalStateException(
                    "Public key file not found at: " + keyPath +
                    ". Generate it with: openssl rsa -in <private-key> -pubout -out " + keyPath);
        }

        String key = new String(Files.readAllBytes(Paths.get(keyPath)))
                .replaceAll("-----BEGIN.*-----", "")
                .replaceAll("-----END.*-----", "")
                .replaceAll("\\s", "");

        byte[] decodedKey = Base64.getDecoder().decode(key);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(decodedKey);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        return keyFactory.generatePublic(spec);
    }
}
