package com.rnd.bridge.intake.unstructured.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.rnd.bridge.intake.unstructured.dto.SourceRecord;
import com.rnd.bridge.shared.unstructured.dto.SourceType;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ObjectKeyBuilderSanityTest {

    private static final Pattern SCHEMA_PATTERN = Pattern.compile("^[A-Z_]+/[^/]+/\\d{4}-\\d{2}-\\d{2}/[^/]+$");

    private final ObjectKeyBuilder builder = new ObjectKeyBuilder();

    @Test
    void driveKeyMatchesOfficialSchemaPattern() {
        SourceRecord record = new SourceRecord(
                SourceType.DRIVE, "kamatech", "9f8e7d6c5b",
                Instant.parse("2026-08-16T20:00:00Z"), "malki@kamatech.org", List.of("malki@kamatech.org"),
                "IMG_2043.jpg", null, "{}", "image/jpeg");

        String key = builder.build(record);

        assertThat(SCHEMA_PATTERN.matcher(key).matches()).isTrue();
        assertThat(key).startsWith("DRIVE/kamatech/2026-08-16/").endsWith(".json");
    }
}
