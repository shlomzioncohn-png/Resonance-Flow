package com.rnd.bridge.intake.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.raw.RawObjectValidator;
import com.rnd.bridge.raw.model.Modality;
import com.rnd.bridge.raw.model.RawObjectEvent;
import com.rnd.bridge.raw.model.RawSourceType;
import com.rnd.bridge.raw.model.ScopeDecision;
import com.rnd.bridge.storage.ObjectStorageClient.PutResult;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

class PointerEventPublisherSanityTest {

    private static final Pattern CONTENT_HASH_PATTERN = Pattern.compile("^sha256:[a-f0-9]{64}$");

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, Object> kafkaTemplate = mock(KafkaTemplate.class);
    private final PointerEventPublisher publisher =
            new PointerEventPublisher(kafkaTemplate, new RawObjectValidator(), "bridge.raw.landed");

    @Test
    void driveImageGetsScannedDocModalityAndValidContentHash() {
        SourceRecord record = new SourceRecord(
                RawSourceType.DRIVE, "kamatech", "9f8e7d6c5b", "corr-1",
                Instant.parse("2026-08-16T20:00:00Z"), "malki@kamatech.org", List.of("malki@kamatech.org"),
                "IMG_2043.jpg", null, "{}".getBytes(StandardCharsets.UTF_8), "image/jpeg", null, 0);

        RawObjectEvent event = publisher.publish(
                "DRIVE/kamatech/2026-08-16/x.json", record, ScopeDecision.INCLUDED,
                new PutResult("image/jpeg", 42L), Instant.now());

        assertThat(event.modality()).isEqualTo(Modality.SCANNED_DOC);
        assertThat(CONTENT_HASH_PATTERN.matcher(event.contentHash()).matches()).isTrue();
        assertThat(event.schemaVersion()).isEqualTo("1.0");
        verify(kafkaTemplate).send(eq("bridge.raw.landed"), eq(event.contentHash()), any(RawObjectEvent.class));
    }

    @Test
    void emailBodyWithoutAttachmentGetsNullModality() {
        SourceRecord record = new SourceRecord(
                RawSourceType.EMAIL, "kamatech", "EMAIL|hadas@kamatech.org|2026-08-18T09:15:00Z", "corr-2",
                Instant.parse("2026-08-18T09:15:00Z"), "hadas@kamatech.org", List.of("hadas@kamatech.org"),
                "PR review needed", "body", "{}".getBytes(StandardCharsets.UTF_8), null, null, 0);

        RawObjectEvent event = publisher.publish(
                "EMAIL/kamatech/2026-08-18/x.json", record, ScopeDecision.INCLUDED,
                new PutResult("application/json", 10L), Instant.now());

        assertThat(event.modality()).isNull();
    }

    @Test
    void attachmentRecordWithAudioMimeTypeGetsAudioModality() {
        SourceRecord record = new SourceRecord(
                RawSourceType.EMAIL, "kamatech", "EMAIL|ruth@kamatech.org|2026-08-18T11:00:00Z|attachment", "corr-3",
                Instant.parse("2026-08-18T11:00:00Z"), "ruth@kamatech.org", List.of("ruth@kamatech.org"),
                null, null, "fake-audio-bytes".getBytes(StandardCharsets.UTF_8), "audio/opus", "voice-note.opus", 1);

        RawObjectEvent event = publisher.publish(
                "EMAIL/kamatech/2026-08-18/corr-3-1-voice-note.opus", record, ScopeDecision.INCLUDED,
                new PutResult("audio/opus", 10L), Instant.now());

        assertThat(event.modality()).isEqualTo(Modality.AUDIO);
    }

    @Test
    void participantsArePassedThroughFromTheSourceRecord() {
        SourceRecord record = new SourceRecord(
                RawSourceType.EMAIL, "kamatech", "id-1", "corr-4", Instant.now(),
                "hadas@kamatech.org", List.of("hadas@kamatech.org"), "subject", "body",
                "{}".getBytes(StandardCharsets.UTF_8), null, null, 0);

        RawObjectEvent event = publisher.publish(
                "EMAIL/kamatech/2026-08-18/z.json", record, ScopeDecision.INCLUDED,
                new PutResult("application/json", 10L), Instant.now());

        assertThat(event.participants()).containsExactly("hadas@kamatech.org");
    }

    @Test
    void sameRawContentProducesSameContentHashDeterministically() {
        SourceRecord record = new SourceRecord(
                RawSourceType.EMAIL, "kamatech", "EMAIL|hadas@kamatech.org|2026-08-18T09:15:00Z", "corr-5",
                Instant.parse("2026-08-18T09:15:00Z"), "hadas@kamatech.org", List.of("hadas@kamatech.org"),
                "PR review needed", "body", "{}".getBytes(StandardCharsets.UTF_8), null, null, 0);

        RawObjectEvent first = publisher.publish(
                "EMAIL/kamatech/2026-08-18/a.json", record, ScopeDecision.INCLUDED,
                new PutResult("application/json", 10L), Instant.now());
        RawObjectEvent second = publisher.publish(
                "EMAIL/kamatech/2026-08-18/b.json", record, ScopeDecision.INCLUDED,
                new PutResult("application/json", 10L), Instant.now());

        assertThat(first.contentHash()).isEqualTo(second.contentHash());
    }
}
