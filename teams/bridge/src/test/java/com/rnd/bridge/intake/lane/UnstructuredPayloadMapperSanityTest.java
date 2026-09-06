package com.rnd.bridge.intake.lane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.intake.service.IntakeLandingService;
import com.rnd.bridge.raw.model.Modality;
import com.rnd.bridge.raw.model.RawObjectEvent;
import com.rnd.bridge.raw.model.RawSourceType;
import com.rnd.bridge.scoping.AllowlistScopingFilter;
import com.rnd.bridge.storage.ObjectKeyBuilder;
import com.rnd.bridge.storage.ObjectStorageClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class UnstructuredPayloadMapperSanityTest {

    private static final Pattern CONTENT_HASH_PATTERN = Pattern.compile("^sha256:[a-f0-9]{64}$");

    // Never stubbed/verified for I/O: map() is pure and must not touch storage - see afterMap().
    private final ObjectStorageClient storageClient = mock(ObjectStorageClient.class);
    private final UnstructuredPayloadMapper mapper = new UnstructuredPayloadMapper(
            new AllowlistScopingFilter(), new ObjectKeyBuilder(), new IntakeLandingService(storageClient));

    @Test
    void mapIsPureAndNeverTouchesStorage() {
        SourceRecord record = new SourceRecord(
                RawSourceType.DRIVE, "kamatech", "9f8e7d6c5b", "corr-1",
                Instant.parse("2026-08-16T20:00:00Z"), "malki@kamatech.org", List.of("malki@kamatech.org"),
                "IMG_2043.jpg", null, "{}".getBytes(StandardCharsets.UTF_8), "image/jpeg", null, 0, List.of());

        RawObjectEvent event = mapper.map(record, Instant.now());

        assertThat(event.modality()).isEqualTo(Modality.SCANNED_DOC);
        assertThat(event.contentType()).isEqualTo("image/jpeg");
        assertThat(event.sizeBytes()).isEqualTo(2L);
        assertThat(CONTENT_HASH_PATTERN.matcher(event.contentHash()).matches()).isTrue();
        assertThat(event.schemaVersion()).isEqualTo("1.0");
        verifyNoInteractions(storageClient);
    }

    @Test
    void emailBodyWithoutAttachmentGetsNullModality() {
        SourceRecord record = new SourceRecord(
                RawSourceType.EMAIL, "kamatech", "EMAIL|hadas@kamatech.org|2026-08-18T09:15:00Z", "corr-2",
                Instant.parse("2026-08-18T09:15:00Z"), "hadas@kamatech.org", List.of("hadas@kamatech.org"),
                "PR review needed", "body", "{}".getBytes(StandardCharsets.UTF_8), null, null, 0, List.of());

        RawObjectEvent event = mapper.map(record, Instant.now());

        assertThat(event.modality()).isNull();
        assertThat(event.contentType()).isEqualTo("application/json");
    }

    @Test
    void attachmentRecordWithAudioMimeTypeGetsAudioModality() {
        SourceRecord record = new SourceRecord(
                RawSourceType.EMAIL, "kamatech", "EMAIL|ruth@kamatech.org|2026-08-18T11:00:00Z|attachment", "corr-3",
                Instant.parse("2026-08-18T11:00:00Z"), "ruth@kamatech.org", List.of("ruth@kamatech.org"),
                null, null, "fake-audio-bytes".getBytes(StandardCharsets.UTF_8), "audio/opus", "voice-note.opus", 1,
                List.of());

        RawObjectEvent event = mapper.map(record, Instant.now());

        assertThat(event.modality()).isEqualTo(Modality.AUDIO);
    }

    @Test
    void participantsArePassedThroughFromTheSourceRecord() {
        SourceRecord record = new SourceRecord(
                RawSourceType.EMAIL, "kamatech", "id-1", "corr-4", Instant.now(),
                "hadas@kamatech.org", List.of("hadas@kamatech.org"), "subject", "body",
                "{}".getBytes(StandardCharsets.UTF_8), null, null, 0, List.of());

        RawObjectEvent event = mapper.map(record, Instant.now());

        assertThat(event.participants()).containsExactly("hadas@kamatech.org");
    }

    @Test
    void sameRawContentProducesSameContentHashDeterministically() {
        SourceRecord record = new SourceRecord(
                RawSourceType.EMAIL, "kamatech", "EMAIL|hadas@kamatech.org|2026-08-18T09:15:00Z", "corr-5",
                Instant.parse("2026-08-18T09:15:00Z"), "hadas@kamatech.org", List.of("hadas@kamatech.org"),
                "PR review needed", "body", "{}".getBytes(StandardCharsets.UTF_8), null, null, 0, List.of());

        RawObjectEvent first = mapper.map(record, Instant.now());
        RawObjectEvent second = mapper.map(record, Instant.now());

        assertThat(first.contentHash()).isEqualTo(second.contentHash());
    }
}
