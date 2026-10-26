package com.rnd.bridge.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.raw.model.RawSourceType;
import com.rnd.bridge.raw.model.Role;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ObjectKeyBuilderSanityTest {

    private static final Pattern SCHEMA_PATTERN = Pattern.compile("^[A-Z_]+/[^/]+/\\d{4}-\\d{2}-\\d{2}/[^/]+$");
    private static final Instant INGESTED_AT = Instant.parse("2026-08-25T12:00:00Z");

    private final ObjectKeyBuilder builder = new ObjectKeyBuilder();

    @Test
    void driveKeyUsesIngestionDateNotSourceOccurredAt() {
        SourceRecord record = new SourceRecord(
                RawSourceType.DRIVE, "kamatech", "9f8e7d6c5b", "corr-1",
                Instant.parse("2026-08-16T20:00:00Z"), "malki@kamatech.org", List.of("malki@kamatech.org"),
                "IMG_2043.jpg", null, "{}".getBytes(StandardCharsets.UTF_8), "image/jpeg", null, 0, 0, List.of(), 0);

        String key = builder.build(record, INGESTED_AT, Role.BODY);

        assertThat(SCHEMA_PATTERN.matcher(key).matches()).isTrue();
        assertThat(key).startsWith("DRIVE/kamatech/2026-08-25/");
        assertThat(key).endsWith(".jpg");
    }

    @Test
    void attachmentKeyEndsWithTheRealOriginalFilename() {
        SourceRecord record = new SourceRecord(
                RawSourceType.EMAIL, "kamatech", "id-1|attachment-1", "corr-1",
                Instant.parse("2026-08-18T11:00:00Z"), "ruth@kamatech.org", List.of("ruth@kamatech.org"),
                null, null, "fake-pdf".getBytes(StandardCharsets.UTF_8), "application/pdf",
                "deal-invoice-scan.pdf", 1, 1, List.of(), 0);

        String key = builder.build(record, INGESTED_AT, Role.ATTACHMENT);

        assertThat(SCHEMA_PATTERN.matcher(key).matches()).isTrue();
        assertThat(key).startsWith("EMAIL/kamatech/2026-08-25/");
        assertThat(key).endsWith("-deal-invoice-scan.pdf");
    }

    @Test
    void twoAttachmentsWithTheSameFilenameOnTheSameMessageGetDistinctKeys() {
        SourceRecord first = new SourceRecord(
                RawSourceType.EMAIL, "kamatech", "id-1|attachment-1", "corr-2",
                Instant.parse("2026-08-18T11:00:00Z"), "ruth@kamatech.org", List.of("ruth@kamatech.org"),
                null, null, "fake-a".getBytes(StandardCharsets.UTF_8), "application/pdf", "invoice.pdf", 1, 2, List.of(), 0);
        SourceRecord second = new SourceRecord(
                RawSourceType.EMAIL, "kamatech", "id-1|attachment-2", "corr-2",
                Instant.parse("2026-08-18T11:00:00Z"), "ruth@kamatech.org", List.of("ruth@kamatech.org"),
                null, null, "fake-b".getBytes(StandardCharsets.UTF_8), "application/pdf", "invoice.pdf", 2, 2, List.of(), 0);

        assertThat(builder.build(first, INGESTED_AT, Role.ATTACHMENT))
                .isNotEqualTo(builder.build(second, INGESTED_AT, Role.ATTACHMENT));
    }

    @Test
    void realEmailBodyExtensionIsTxtButSourceMetadataStaysJson() {
        SourceRecord body = new SourceRecord(
                RawSourceType.EMAIL, "kamatech", "id-1", "corr-3",
                Instant.parse("2026-08-18T11:00:00Z"), "ruth@kamatech.org", List.of("ruth@kamatech.org"),
                "subject", "From: ruth@kamatech.org\n\nhi", "body".getBytes(StandardCharsets.UTF_8),
                "text/plain; charset=UTF-8", null, 0, 0, List.of(), 0);
        SourceRecord metadata = new SourceRecord(
                RawSourceType.EMAIL, "kamatech", "id-1|metadata", "corr-3",
                Instant.parse("2026-08-18T11:00:00Z"), "ruth@kamatech.org", List.of("ruth@kamatech.org"),
                "subject", null, "{}".getBytes(StandardCharsets.UTF_8), "application/json", null, 0, 0, List.of(), 0)
                .withRole(Role.SOURCE_METADATA);

        assertThat(builder.build(body, INGESTED_AT, Role.BODY)).endsWith(".txt");
        assertThat(builder.build(metadata, INGESTED_AT, Role.SOURCE_METADATA)).endsWith(".json");
    }

    @Test
    void fixtureEmailBodyWithNoExplicitMimeTypeStaysJson() {
        // FixtureEmailSourceReader's body record never sets mimeType (stays null) - must keep
        // its original ".json" extension unchanged by the Real-reader-only txt/json split above.
        SourceRecord body = new SourceRecord(
                RawSourceType.EMAIL, "kamatech", "id-1", "corr-5",
                Instant.parse("2026-08-18T11:00:00Z"), "ruth@kamatech.org", List.of("ruth@kamatech.org"),
                "subject", "fixture body text", "{}".getBytes(StandardCharsets.UTF_8), null, null, 0, 0,
                List.of(), 0);

        assertThat(builder.build(body, INGESTED_AT, Role.BODY)).endsWith(".json");
    }

    @Test
    void driveExtensionDependsOnMimeTypeNotRole() {
        SourceRecord record = new SourceRecord(
                RawSourceType.DRIVE, "kamatech", "9f8e7d6c5b", "corr-4",
                Instant.parse("2026-08-16T20:00:00Z"), "malki@kamatech.org", List.of("malki@kamatech.org"),
                "scan.pdf", null, "fake-pdf".getBytes(StandardCharsets.UTF_8), "application/pdf", null, 0, 0,
                List.of(), 0);

        assertThat(builder.build(record, INGESTED_AT, Role.BODY)).endsWith(".pdf");
        assertThat(builder.build(record, INGESTED_AT, Role.SOURCE_METADATA)).endsWith(".pdf");
    }

    @Test
    void driveNativeGoogleFileExportedAsTextPlainGetsTxtExtension() {
        SourceRecord record = new SourceRecord(
                RawSourceType.DRIVE, "kamatech", "doc1", "corr-6",
                Instant.parse("2026-08-16T20:00:00Z"), "malki@kamatech.org", List.of("malki@kamatech.org"),
                "notes.doc", null, "exported text".getBytes(StandardCharsets.UTF_8), "text/plain", null, 0, 0,
                List.of(), 0);

        assertThat(builder.build(record, INGESTED_AT, Role.BODY)).endsWith(".txt");
    }

    @Test
    void driveUnrecognizedOrNullMimeTypeDefaultsToJson() {
        SourceRecord unrecognized = new SourceRecord(
                RawSourceType.DRIVE, "kamatech", "f1", "corr-7",
                Instant.parse("2026-08-16T20:00:00Z"), "malki@kamatech.org", List.of("malki@kamatech.org"),
                "weird.bin", null, "bytes".getBytes(StandardCharsets.UTF_8), "application/octet-stream", null, 0, 0,
                List.of(), 0);
        // FixtureDriveSourceReader never sets mimeType on its records (stays null) - must keep
        // its original ".json" extension, since its content is genuinely the fixture JSON text.
        SourceRecord fixture = new SourceRecord(
                RawSourceType.DRIVE, "kamatech", "f2", "corr-8",
                Instant.parse("2026-08-16T20:00:00Z"), "malki@kamatech.org", List.of("malki@kamatech.org"),
                "fixture.json", null, "{}".getBytes(StandardCharsets.UTF_8), null, null, 0, 0, List.of(), 0);

        assertThat(builder.build(unrecognized, INGESTED_AT, Role.BODY)).endsWith(".json");
        assertThat(builder.build(fixture, INGESTED_AT, Role.BODY)).endsWith(".json");
    }
}
