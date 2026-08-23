package com.rnd.bridge.intake.unstructured.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.rnd.bridge.intake.unstructured.dto.SourceRecord;
import com.rnd.bridge.intake.unstructured.service.MinioLandingService.LandingResult;
import com.rnd.bridge.shared.unstructured.dto.Modality;
import com.rnd.bridge.shared.unstructured.dto.RawObjectEvent;
import com.rnd.bridge.shared.unstructured.dto.ScopeDecision;
import com.rnd.bridge.shared.unstructured.dto.SourceType;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

class PointerEventPublisherSanityTest {

    private static final Pattern CONTENT_HASH_PATTERN = Pattern.compile("^sha256:[a-f0-9]{64}$");

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
    private final PointerEventPublisher publisher = new PointerEventPublisher(kafkaTemplate, "bridge.raw.landed");

    @Test
    void driveImageGetsScannedDocModalityAndValidContentHash() {
        SourceRecord record = new SourceRecord(
                SourceType.DRIVE, "kamatech", "9f8e7d6c5b",
                Instant.parse("2026-08-16T20:00:00Z"), "malki@kamatech.org", List.of("malki@kamatech.org"),
                "IMG_2043.jpg", null, "{}", "image/jpeg");

        RawObjectEvent event = publisher.publish(
                "DRIVE/kamatech/2026-08-16/x.json", record, ScopeDecision.INCLUDED,
                new LandingResult("image/jpeg", 42L));

        assertThat(event.modality()).isEqualTo(Modality.SCANNED_DOC);
        assertThat(CONTENT_HASH_PATTERN.matcher(event.contentHash()).matches()).isTrue();
        assertThat(event.schemaVersion()).isEqualTo("1.0");
        verify(kafkaTemplate).send(eq("bridge.raw.landed"), eq(event.contentHash()), any(RawObjectEvent.class));
    }

    @Test
    void emailWithoutAttachmentGetsNullModality() {
        SourceRecord record = new SourceRecord(
                SourceType.EMAIL, "kamatech", "EMAIL|hadas@kamatech.org|2026-08-18T09:15:00Z",
                Instant.parse("2026-08-18T09:15:00Z"), "hadas@kamatech.org", List.of("hadas@kamatech.org"),
                "PR review needed", "body", "{}", null);

        RawObjectEvent event = publisher.publish(
                "EMAIL/kamatech/2026-08-18/x.json", record, ScopeDecision.INCLUDED,
                new LandingResult("application/json", 10L));

        assertThat(event.modality()).isNull();
    }

    @Test
    void emailWithAudioAttachmentGetsAudioModality() {
        SourceRecord record = new SourceRecord(
                SourceType.EMAIL, "kamatech", "EMAIL|ruth@kamatech.org|2026-08-18T11:00:00Z",
                Instant.parse("2026-08-18T11:00:00Z"), "ruth@kamatech.org", List.of("ruth@kamatech.org"),
                "voice memo", "body", "{}", "audio/opus");

        RawObjectEvent event = publisher.publish(
                "EMAIL/kamatech/2026-08-18/y.json", record, ScopeDecision.INCLUDED,
                new LandingResult("application/json", 10L));

        assertThat(event.modality()).isEqualTo(Modality.AUDIO);
    }

    @Test
    void participantsArePassedThroughFromTheSourceRecord() {
        SourceRecord record = new SourceRecord(
                SourceType.EMAIL, "kamatech", "id-1", Instant.now(),
                "hadas@kamatech.org", List.of("hadas@kamatech.org"), "subject", "body", "{}", null);

        RawObjectEvent event = publisher.publish(
                "EMAIL/kamatech/2026-08-18/z.json", record, ScopeDecision.INCLUDED,
                new LandingResult("application/json", 10L));

        assertThat(event.participants()).containsExactly("hadas@kamatech.org");
    }

    @Test
    void sameRawContentProducesSameContentHashDeterministically() {
        SourceRecord record = new SourceRecord(
                SourceType.EMAIL, "kamatech", "EMAIL|hadas@kamatech.org|2026-08-18T09:15:00Z",
                Instant.parse("2026-08-18T09:15:00Z"), "hadas@kamatech.org", List.of("hadas@kamatech.org"),
                "PR review needed", "body", "{}", null);

        RawObjectEvent first = publisher.publish(
                "EMAIL/kamatech/2026-08-18/a.json", record, ScopeDecision.INCLUDED,
                new LandingResult("application/json", 10L));
        RawObjectEvent second = publisher.publish(
                "EMAIL/kamatech/2026-08-18/b.json", record, ScopeDecision.INCLUDED,
                new LandingResult("application/json", 10L));

        assertThat(first.contentHash()).isEqualTo(second.contentHash());
    }
}
