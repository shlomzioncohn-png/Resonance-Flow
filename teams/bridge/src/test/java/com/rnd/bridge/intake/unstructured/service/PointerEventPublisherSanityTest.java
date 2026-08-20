package com.rnd.bridge.intake.unstructured.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.rnd.bridge.intake.unstructured.dto.Modality;
import com.rnd.bridge.intake.unstructured.dto.RawObjectEvent;
import com.rnd.bridge.intake.unstructured.dto.ScopeDecision;
import com.rnd.bridge.intake.unstructured.dto.SourceRecord;
import com.rnd.bridge.intake.unstructured.dto.SourceType;
import com.rnd.bridge.intake.unstructured.service.MinioLandingService.LandingResult;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

class PointerEventPublisherSanityTest {

    private static final Pattern DEDUP_KEY_PATTERN = Pattern.compile("^sha256:[a-f0-9]{64}$");

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
    private final PointerEventPublisher publisher = new PointerEventPublisher(kafkaTemplate, "bridge.raw.landed");

    @Test
    void driveImageGetsImageModalityAndValidDedupKey() {
        SourceRecord record = new SourceRecord(
                SourceType.DRIVE_DOC, "kamatech", "9f8e7d6c5b",
                Instant.parse("2026-08-16T20:00:00Z"), "malki@kamatech.org",
                "IMG_2043.jpg", null, "{}", "image/jpeg");
        UUID traceId = UUID.randomUUID();

        RawObjectEvent event = publisher.publish(
                "DRIVE_DOC/kamatech/2026-08-16/x.json", record, ScopeDecision.INCLUDED,
                new LandingResult("image/jpeg", 42L), traceId);

        assertThat(event.modality()).isEqualTo(Modality.IMAGE);
        assertThat(DEDUP_KEY_PATTERN.matcher(event.dedupKey()).matches()).isTrue();
        assertThat(event.schemaVersion()).isEqualTo("1.0");
        assertThat(event.traceId()).isEqualTo(traceId);
        verify(kafkaTemplate).send(eq("bridge.raw.landed"), eq(event.dedupKey()), any(RawObjectEvent.class));
    }

    @Test
    void emailGetsTextModality() {
        SourceRecord record = new SourceRecord(
                SourceType.EMAIL, "kamatech", "EMAIL|hadas@kamatech.org|2026-08-18T09:15:00Z",
                Instant.parse("2026-08-18T09:15:00Z"), "hadas@kamatech.org",
                "PR review needed", "body", "{}", null);

        RawObjectEvent event = publisher.publish(
                "EMAIL/kamatech/2026-08-18/x.json", record, ScopeDecision.INCLUDED,
                new LandingResult("application/json", 10L), UUID.randomUUID());

        assertThat(event.modality()).isEqualTo(Modality.TEXT);
    }

    @Test
    void sameNaturalIdProducesSameDedupKeyDeterministically() {
        SourceRecord record = new SourceRecord(
                SourceType.EMAIL, "kamatech", "EMAIL|hadas@kamatech.org|2026-08-18T09:15:00Z",
                Instant.parse("2026-08-18T09:15:00Z"), "hadas@kamatech.org",
                "PR review needed", "body", "{}", null);

        RawObjectEvent first = publisher.publish(
                "EMAIL/kamatech/2026-08-18/a.json", record, ScopeDecision.INCLUDED,
                new LandingResult("application/json", 10L), UUID.randomUUID());
        RawObjectEvent second = publisher.publish(
                "EMAIL/kamatech/2026-08-18/b.json", record, ScopeDecision.INCLUDED,
                new LandingResult("application/json", 10L), UUID.randomUUID());

        assertThat(first.dedupKey()).isEqualTo(second.dedupKey());
    }
}
