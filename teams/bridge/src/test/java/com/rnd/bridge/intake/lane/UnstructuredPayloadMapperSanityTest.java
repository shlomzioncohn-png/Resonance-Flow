package com.rnd.bridge.intake.lane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.intake.service.IntakeLandingService;
import com.rnd.bridge.intake.service.SourceRecordRefMapper;
import com.rnd.bridge.raw.model.ChangeType;
import com.rnd.bridge.raw.model.Modality;
import com.rnd.bridge.raw.model.RawObjectEvent;
import com.rnd.bridge.raw.model.RawObjectRef;
import com.rnd.bridge.raw.model.RawSourceType;
import com.rnd.bridge.raw.model.Role;
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
    private final SourceRecordRefMapper refMapper = new SourceRecordRefMapper(
            new ObjectKeyBuilder(), new IntakeLandingService(storageClient));
    private final UnstructuredPayloadMapper mapper =
            new UnstructuredPayloadMapper(new AllowlistScopingFilter(), refMapper);

    @Test
    void mapIsPureAndNeverTouchesStorage() {
        SourceRecord record = new SourceRecord(
                RawSourceType.DRIVE, "kamatech", "9f8e7d6c5b", "corr-1",
                Instant.parse("2026-08-16T20:00:00Z"), "malki@kamatech.org", List.of("malki@kamatech.org"),
                "IMG_2043.jpg", null, "{}".getBytes(StandardCharsets.UTF_8), "image/jpeg", null, 0, 0, List.of(), 0);

        RawObjectEvent event = mapper.map(List.of(record), Instant.now());
        RawObjectRef ref = event.objects().get(0);

        assertThat(ref.modality()).isEqualTo(Modality.SCANNED_DOC);
        assertThat(ref.contentType()).isEqualTo("image/jpeg");
        assertThat(ref.sizeBytes()).isEqualTo(2L);
        assertThat(CONTENT_HASH_PATTERN.matcher(ref.contentHash()).matches()).isTrue();
        assertThat(event.schemaVersion()).isEqualTo("1.0");
        verifyNoInteractions(storageClient);
    }

    @Test
    void emailBodyWithoutAttachmentGetsTextModality() {
        SourceRecord record = new SourceRecord(
                RawSourceType.EMAIL, "kamatech", "EMAIL|hadas@kamatech.org|2026-08-18T09:15:00Z", "corr-2",
                Instant.parse("2026-08-18T09:15:00Z"), "hadas@kamatech.org", List.of("hadas@kamatech.org"),
                "PR review needed", "body", "{}".getBytes(StandardCharsets.UTF_8), null, null, 0, 0, List.of(), 0);

        RawObjectEvent event = mapper.map(List.of(record), Instant.now());
        RawObjectRef ref = event.objects().get(0);

        assertThat(ref.modality()).isEqualTo(Modality.TEXT);
        assertThat(ref.contentType()).isEqualTo("application/json");
        assertThat(ref.role()).isEqualTo(Role.BODY);
    }

    @Test
    void anEmailBodyPlusItsAttachmentMapToOneEventWithTwoObjects() {
        SourceRecord body = new SourceRecord(
                RawSourceType.EMAIL, "kamatech", "EMAIL|ruth@kamatech.org|2026-08-18T11:00:00Z", "corr-3",
                Instant.parse("2026-08-18T11:00:00Z"), "ruth@kamatech.org", List.of("ruth@kamatech.org"),
                "invoice", "body text", "body text".getBytes(StandardCharsets.UTF_8), null, null, 0, 1, List.of(), 0);
        SourceRecord attachment = new SourceRecord(
                RawSourceType.EMAIL, "kamatech", "EMAIL|ruth@kamatech.org|2026-08-18T11:00:00Z|attachment", "corr-3",
                Instant.parse("2026-08-18T11:00:00Z"), "ruth@kamatech.org", List.of("ruth@kamatech.org"),
                null, null, "fake-audio-bytes".getBytes(StandardCharsets.UTF_8), "audio/opus", "voice-note.opus", 1,
                1, List.of(), 0);

        RawObjectEvent event = mapper.map(List.of(body, attachment), Instant.now());

        assertThat(event.objects()).hasSize(2);
        assertThat(event.objects().get(0).role()).isEqualTo(Role.BODY);
        assertThat(event.objects().get(1).role()).isEqualTo(Role.ATTACHMENT);
        assertThat(event.objects().get(1).modality()).isEqualTo(Modality.AUDIO);
        assertThat(event.correlationId()).isEqualTo("corr-3");
    }

    @Test
    void outerEventFieldsComeFromTheGroupsPrimaryBodyRecordNotAnAttachment() {
        SourceRecord body = new SourceRecord(
                RawSourceType.EMAIL, "kamatech", "id-1", "corr-4", Instant.parse("2026-08-18T09:00:00Z"),
                "hadas@kamatech.org", List.of("hadas@kamatech.org"), "subject", "body",
                "{}".getBytes(StandardCharsets.UTF_8), null, null, 0, 1, List.of(), 0);
        SourceRecord attachment = new SourceRecord(
                RawSourceType.EMAIL, "kamatech", "id-1|attachment-1", "corr-4", Instant.parse("2026-08-18T09:00:00Z"),
                "other-sender", List.of("other-sender"), null, null,
                "bytes".getBytes(StandardCharsets.UTF_8), "application/pdf", "invoice.pdf", 1, 1, List.of(), 0);

        RawObjectEvent event = mapper.map(List.of(attachment, body), Instant.now()); // attachment listed first

        assertThat(event.participants()).containsExactly("hadas@kamatech.org");
    }

    @Test
    void sameRawContentProducesSameContentHashDeterministically() {
        SourceRecord record = new SourceRecord(
                RawSourceType.EMAIL, "kamatech", "EMAIL|hadas@kamatech.org|2026-08-18T09:15:00Z", "corr-5",
                Instant.parse("2026-08-18T09:15:00Z"), "hadas@kamatech.org", List.of("hadas@kamatech.org"),
                "PR review needed", "body", "{}".getBytes(StandardCharsets.UTF_8), null, null, 0, 0, List.of(), 0);

        RawObjectEvent first = mapper.map(List.of(record), Instant.now());
        RawObjectEvent second = mapper.map(List.of(record), Instant.now());

        assertThat(first.objects().get(0).contentHash()).isEqualTo(second.objects().get(0).contentHash());
    }

    @Test
    void sourceRecordRevisionIsCarriedThroughToTheRawObjectEventVerbatim() {
        SourceRecord record = new SourceRecord(
                RawSourceType.SLACK, "kamatech", "chat-a|2026-08-18T09:00:00Z", "corr-6",
                Instant.parse("2026-08-18T09:00:00Z"), "ruth", List.of("ruth"), null, "ruth: hi (edited)",
                "ruth: hi (edited)".getBytes(StandardCharsets.UTF_8), null, null, 0, 0, List.of("m1"), 3);

        RawObjectEvent event = mapper.map(List.of(record), Instant.now());

        assertThat(event.revision()).isEqualTo(3);
    }

    @Test
    void anAttachmentRecordIsMappedToAnAttachmentRoleRef() {
        SourceRecord record = new SourceRecord(
                RawSourceType.EMAIL, "kamatech", "id-1|attachment-1", "corr-8",
                Instant.parse("2026-08-18T11:00:00Z"), "ruth@kamatech.org", List.of("ruth@kamatech.org"),
                null, null, "fake-pdf".getBytes(StandardCharsets.UTF_8), "application/pdf", "invoice.pdf", 1,
                2, List.of(), 0);

        RawObjectEvent event = mapper.map(List.of(record), Instant.now());

        assertThat(event.objects().get(0).role()).isEqualTo(Role.ATTACHMENT);
    }

    @Test
    void newAndUnchangedRecordsAreEmittedAsUpsert() {
        SourceRecord record = new SourceRecord(
                RawSourceType.EMAIL, "kamatech", "id-1", "corr-7", Instant.now(),
                "hadas@kamatech.org", List.of("hadas@kamatech.org"), "subject", "body",
                "{}".getBytes(StandardCharsets.UTF_8), null, null, 0, 0, List.of(), 0);

        RawObjectEvent event = mapper.map(List.of(record), Instant.now());

        assertThat(event.changeType()).isEqualTo(ChangeType.UPSERT);
    }
}
