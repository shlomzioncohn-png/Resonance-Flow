package com.rnd.gateway;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;

/**
 * The Gateway's RSA signing key, for tests. JwtConfig reads the key pair from
 * PEM files (lens.jwt.*-key-location), and real keys are never committed
 * (*.pem is git-ignored) - so CI has none. This generates one pair per test
 * run, writes it under target/, and points lens.jwt.* at it for every test
 * context (registered in src/test/resources/META-INF/spring.factories).
 *
 * Tests that sign their own tokens use {@link #rsaKey()} - the same key the
 * Gateway validates with.
 */
public class TestJwtKeys implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final Path DIR = Path.of("target", "test-jwt");
    private static final Path PRIVATE_KEY = DIR.resolve("jwt-private.pem");
    private static final Path PUBLIC_KEY = DIR.resolve("jwt-public.pem");

    private static RSAKey key;

    /** The key pair the Gateway signs and validates with during tests. */
    public static synchronized RSAKey rsaKey() {
        if (key == null) {
            try {
                key = new RSAKeyGenerator(2048).keyID("gateway-test-key").generate();
                Files.createDirectories(DIR);
                Files.writeString(PRIVATE_KEY, pem("PRIVATE KEY", key.toRSAPrivateKey().getEncoded()));
                Files.writeString(PUBLIC_KEY, pem("PUBLIC KEY", key.toRSAPublicKey().getEncoded()));
            } catch (JOSEException ex) {
                throw new IllegalStateException("Could not generate the test signing key", ex);
            } catch (IOException ex) {
                throw new UncheckedIOException("Could not write the test signing key", ex);
            }
        }
        return key;
    }

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        rsaKey();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test-jwt-keys", Map.of(
                "lens.jwt.private-key-location", PRIVATE_KEY.toAbsolutePath().toString(),
                "lens.jwt.public-key-location", PUBLIC_KEY.toAbsolutePath().toString())));
    }

    private static String pem(String type, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----\n";
    }
}
