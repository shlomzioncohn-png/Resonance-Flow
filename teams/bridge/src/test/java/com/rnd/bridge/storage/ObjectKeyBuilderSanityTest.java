package com.rnd.bridge.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.raw.model.RawSourceType;
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
                "IMG_2043.jpg", null, "{}".getBytes(StandardCharsets.UTF_8), "image/jpeg", null, 0, List.of());

        String key = builder.build(record, INGESTED_AT);

        assertThat(SCHEMA_PATTERN.matcher(key).matches()).isTrue();
        assertThat(key).startsWith("DRIVE/kamatech/2026-08-25/");
        assertThat(key).endsWith(".json");
    }

    @Test
    void attachmentKeyEndsWithTheRealOriginalFilename() {
        SourceRecord record = new SourceRecord(
                RawSourceType.EMAIL, "kamatech", "id-1|attachment-1", "corr-1",
                Instant.parse("2026-08-18T11:00:00Z"), "ruth@kamatech.org", List.of("ruth@kamatech.org"),
                null, null, "fake-pdf".getBytes(StandardCharsets.UTF_8), "application/pdf",
                "deal-invoice-scan.pdf", 1, List.of());

        String key = builder.build(record, INGESTED_AT);

        assertThat(SCHEMA_PATTERN.matcher(key).matches()).isTrue();
        assertThat(key).startsWith("EMAIL/kamatech/2026-08-25/");
        assertThat(key).endsWith("-deal-invoice-scan.pdf");
    }

    @Test
    void twoAttachmentsWithTheSameFilenameOnTheSameMessageGetDistinctKeys() {
        SourceRecord first = new SourceRecord(
                RawSourceType.EMAIL, "kamatech", "id-1|attachment-1", "corr-2",
                Instant.parse("2026-08-18T11:00:00Z"), "ruth@kamatech.org", List.of("ruth@kamatech.org"),
                null, null, "fake-a".getBytes(StandardCharsets.UTF_8), "application/pdf", "invoice.pdf", 1, List.of());
        SourceRecord second = new SourceRecord(
                RawSourceType.EMAIL, "kamatech", "id-1|attachment-2", "corr-2",
                Instant.parse("2026-08-18T11:00:00Z"), "ruth@kamatech.org", List.of("ruth@kamatech.org"),
                null, null, "fake-b".getBytes(StandardCharsets.UTF_8), "application/pdf", "invoice.pdf", 2, List.of());

        assertThat(builder.build(first, INGESTED_AT)).isNotEqualTo(builder.build(second, INGESTED_AT));
    }
}
