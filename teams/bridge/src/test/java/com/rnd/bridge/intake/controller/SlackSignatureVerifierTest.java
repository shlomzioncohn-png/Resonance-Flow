package com.rnd.bridge.intake.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.rnd.bridge.intake.config.SlackApiProperties;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class SlackSignatureVerifierTest {

    private static final String SIGNING_SECRET = "8f742231b10e8888abcd99yyyzzz85a";
    private static final Instant NOW = Instant.parse("2026-09-10T12:00:00Z");
    private static final String RAW_BODY = "{\"type\":\"event_callback\",\"event\":{\"type\":\"message\"}}";

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final SlackSignatureVerifier verifier =
            new SlackSignatureVerifier(new SlackApiProperties(null, "kamatech", List.of(), SIGNING_SECRET), clock);

    @Test
    void validSignatureAccepted() {
        String timestamp = String.valueOf(NOW.getEpochSecond());
        String signature = independentlyComputedSignature(timestamp, RAW_BODY);

        assertThat(verifier.isValid(timestamp, signature, RAW_BODY)).isTrue();
    }

    @Test
    void invalidSignatureRejected() {
        String timestamp = String.valueOf(NOW.getEpochSecond());

        assertThat(verifier.isValid(timestamp, "v0=deadbeef", RAW_BODY)).isFalse();
    }

    @Test
    void signatureComputedOverDifferentBodyIsRejected() {
        String timestamp = String.valueOf(NOW.getEpochSecond());
        String signature = independentlyComputedSignature(timestamp, RAW_BODY);

        assertThat(verifier.isValid(timestamp, signature, RAW_BODY + "tampered")).isFalse();
    }

    @Test
    void missingSignatureRejected() {
        String timestamp = String.valueOf(NOW.getEpochSecond());

        assertThat(verifier.isValid(timestamp, null, RAW_BODY)).isFalse();
    }

    @Test
    void missingTimestampRejected() {
        String signature = independentlyComputedSignature(String.valueOf(NOW.getEpochSecond()), RAW_BODY);

        assertThat(verifier.isValid(null, signature, RAW_BODY)).isFalse();
    }

    @Test
    void malformedTimestampRejected() {
        String signature = independentlyComputedSignature("not-a-number", RAW_BODY);

        assertThat(verifier.isValid("not-a-number", signature, RAW_BODY)).isFalse();
    }

    @Test
    void timestampOlderThan5MinutesRejected() {
        Instant tooOld = NOW.minusSeconds(301);
        String timestamp = String.valueOf(tooOld.getEpochSecond());
        String signature = independentlyComputedSignature(timestamp, RAW_BODY);

        assertThat(verifier.isValid(timestamp, signature, RAW_BODY)).isFalse();
    }

    @Test
    void timestampTooFarInFutureRejected() {
        Instant tooFuture = NOW.plusSeconds(301);
        String timestamp = String.valueOf(tooFuture.getEpochSecond());
        String signature = independentlyComputedSignature(timestamp, RAW_BODY);

        assertThat(verifier.isValid(timestamp, signature, RAW_BODY)).isFalse();
    }

    @Test
    void timestampExactlyAtReplayWindowBoundaryAccepted() {
        Instant atBoundary = NOW.minusSeconds(300);
        String timestamp = String.valueOf(atBoundary.getEpochSecond());
        String signature = independentlyComputedSignature(timestamp, RAW_BODY);

        assertThat(verifier.isValid(timestamp, signature, RAW_BODY)).isTrue();
    }

    @Test
    void timestampOneSecondPastReplayWindowBoundaryRejected() {
        Instant justPastBoundary = NOW.minusSeconds(301);
        String timestamp = String.valueOf(justPastBoundary.getEpochSecond());
        String signature = independentlyComputedSignature(timestamp, RAW_BODY);

        assertThat(verifier.isValid(timestamp, signature, RAW_BODY)).isFalse();
    }

    /**
     * Computes a signature the same way Slack itself would, independently of
     * SlackSignatureVerifier's own implementation, so these tests don't just check that the
     * production code agrees with itself.
     */
    private static String independentlyComputedSignature(String timestamp, String body) {
        String baseString = "v0:" + timestamp + ":" + body;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SIGNING_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(baseString.getBytes(StandardCharsets.UTF_8));
            return "v0=" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
