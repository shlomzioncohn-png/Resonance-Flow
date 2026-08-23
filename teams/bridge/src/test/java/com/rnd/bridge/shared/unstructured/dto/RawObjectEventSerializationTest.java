package com.rnd.bridge.shared.unstructured.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Proves the wire format actually matches the official schema's snake_case field names —
 * not just that the Java record compiles, but that @JsonNaming really produces
 * object_key/content_hash/etc. on the JSON that gets published to Kafka.
 */
class RawObjectEventSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void serializesFieldNamesAsSnakeCase() throws Exception {
        RawObjectEvent event = RawObjectEvent.create(
                "EMAIL/kamatech/2026-08-18/x.json",
                SourceType.EMAIL,
                "kamatech",
                Instant.parse("2026-08-18T09:15:00Z"),
                ScopeDecision.INCLUDED,
                "sha256:" + "a".repeat(64),
                Modality.AUDIO,
                List.of("hadas@kamatech.org"),
                "application/json",
                123L,
                Instant.parse("2026-08-18T09:15:00Z"));

        String json = objectMapper.writeValueAsString(event);

        assertThat(json).contains("\"object_key\"");
        assertThat(json).contains("\"source_type\"");
        assertThat(json).contains("\"tenant_id\"");
        assertThat(json).contains("\"ingested_at\"");
        assertThat(json).contains("\"scope_decision\"");
        assertThat(json).contains("\"content_hash\"");
        assertThat(json).contains("\"schema_version\"");
        assertThat(json).contains("\"content_type\"");
        assertThat(json).contains("\"size_bytes\"");
        assertThat(json).contains("\"source_timestamp\"");
        assertThat(json).contains("\"transcript_object_key\"");

        assertThat(json).doesNotContain("\"objectKey\"");
        assertThat(json).doesNotContain("\"contentHash\"");
        assertThat(json).doesNotContain("\"sourceType\"");
    }

    @Test
    void serializesModalityValuesLowercase() throws Exception {
        RawObjectEvent event = RawObjectEvent.create(
                "EMAIL/kamatech/2026-08-18/x.json", SourceType.EMAIL, "kamatech",
                Instant.now(), ScopeDecision.INCLUDED, "sha256:" + "a".repeat(64),
                Modality.SCANNED_DOC, List.of(), null, null, Instant.now());

        String json = objectMapper.writeValueAsString(event);

        assertThat(json).contains("\"modality\":\"scanned_doc\"");
    }
}
