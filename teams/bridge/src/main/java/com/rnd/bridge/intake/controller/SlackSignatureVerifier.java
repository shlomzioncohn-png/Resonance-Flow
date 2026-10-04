package com.rnd.bridge.intake.controller;

import com.rnd.bridge.intake.config.SlackApiProperties;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Verifies Slack Events API requests per Slack's signing-secret scheme:
 * https://api.slack.com/authentication/verifying-requests-from-slack
 *
 * baseString = "v0:" + timestamp + ":" + rawBody, expected = "v0=" + hex(HMAC-SHA256(signingSecret,
 * baseString)), compared against X-Slack-Signature. Must run against the exact raw body bytes
 * Slack sent — any re-serialization (e.g. via a DTO round-trip) would produce a byte sequence
 * that no longer matches Slack's own signature, so callers must pass the untouched request body.
 *
 * clock is injected (not Instant.now()) so the 5-minute replay window is deterministically
 * testable without sleeping/mocking static time.
 */
@Component
public class SlackSignatureVerifier {

    private static final String VERSION_PREFIX = "v0";
    private static final Duration MAX_REPLAY_WINDOW = Duration.ofMinutes(5);
    private static final HexFormat HEX = HexFormat.of();

    private final SlackApiProperties properties;
    private final Clock clock;

    public SlackSignatureVerifier(SlackApiProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Returns true only if timestampHeader/signatureHeader are present and well-formed, the
     * timestamp is within the replay window, and the signature matches rawBody under the
     * configured signing secret. Never throws — any malformed input is treated as "not valid"
     * rather than propagated, since a forged/broken request is expected traffic here, not a
     * bug.
     */
    public boolean isValid(String timestampHeader, String signatureHeader, String rawBody) {
        if (timestampHeader == null || signatureHeader == null) {
            return false;
        }
        long timestampEpochSeconds;
        try {
            timestampEpochSeconds = Long.parseLong(timestampHeader.trim());
        } catch (NumberFormatException e) {
            return false;
        }

        Duration skew = Duration.between(Instant.ofEpochSecond(timestampEpochSeconds), clock.instant()).abs();
        if (skew.compareTo(MAX_REPLAY_WINDOW) > 0) {
            return false;
        }

        String expected = expectedSignature(timestampHeader, rawBody);
        return constantTimeEquals(expected, signatureHeader);
    }

    private String expectedSignature(String timestampHeader, String rawBody) {
        String baseString = VERSION_PREFIX + ":" + timestampHeader + ":" + rawBody;
        return VERSION_PREFIX + "=" + HEX.formatHex(hmacSha256(properties.signingSecret(), baseString));
    }

    private static byte[] hmacSha256(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            // HmacSHA256 is always available on the JVM; a real signing secret is always a
            // valid HMAC key. Reaching here means broken configuration, not attacker input.
            throw new IllegalStateException("Unable to compute Slack signature", e);
        }
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }
}
