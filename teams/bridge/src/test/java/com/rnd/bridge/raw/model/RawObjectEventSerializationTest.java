package com.rnd.bridge.raw.model;

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
                RawSourceType.EMAIL,
                "kamatech",
                Instant.parse("2026-08-18T09:15:00Z"),
                ScopeDecision.INCLUDED,
                "sha256:" + "a".repeat(64),
                Modality.AUDIO,
                List.of("hadas@kamatech.org"),
                "application/json",
                123L,
                Instant.parse("2026-08-18T09:15:00Z"),
                "corr-1",
                0,
                0,
                0,
                ChangeType.UPSERT);

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
        assertThat(json).contains("\"correlation_id\"");
        assertThat(json).contains("\"attachment_index\"");
        assertThat(json).contains("\"attachment_count\"");
        assertThat(json).contains("\"revision\"");
        assertThat(json).contains("\"change_type\"");

        assertThat(json).doesNotContain("\"objectKey\"");
        assertThat(json).doesNotContain("\"contentHash\"");
        assertThat(json).doesNotContain("\"sourceType\"");
        assertThat(json).doesNotContain("\"changeType\"");
        assertThat(json).doesNotContain("\"attachmentCount\"");
    }

    @Test
    void serializesModalityValuesUppercase() throws Exception {
        RawObjectEvent event = RawObjectEvent.create(
                "EMAIL/kamatech/2026-08-18/x.json", RawSourceType.EMAIL, "kamatech",
                Instant.now(), ScopeDecision.INCLUDED, "sha256:" + "a".repeat(64),
                Modality.SCANNED_DOC, List.of(), null, null, Instant.now(), "corr-1", 0, 0, 0, ChangeType.UPSERT);

        String json = objectMapper.writeValueAsString(event);

        assertThat(json).contains("\"modality\":\"SCANNED_DOC\"");
    }

    @Test
    void serializesSourceTypeAndScopeDecisionValuesUppercase() throws Exception {
        RawObjectEvent event = RawObjectEvent.create(
                "EMAIL/kamatech/2026-08-18/x.json", RawSourceType.EMAIL, "kamatech",
                Instant.now(), ScopeDecision.INCLUDED, "sha256:" + "a".repeat(64),
                Modality.AUDIO, List.of(), null, null, Instant.now(), "corr-1", 0, 0, 0, ChangeType.UPSERT);

        String json = objectMapper.writeValueAsString(event);

        assertThat(json).contains("\"source_type\":\"EMAIL\"");
        assertThat(json).contains("\"scope_decision\":\"INCLUDED\"");
    }

    @Test
    void serializesRevisionAndChangeTypeAsSnakeCaseWithUppercaseEnumValue() throws Exception {
        RawObjectEvent event = RawObjectEvent.create(
                "SLACK/kamatech/2026-08-18/x.json", RawSourceType.SLACK, "kamatech",
                Instant.now(), ScopeDecision.INCLUDED, "sha256:" + "a".repeat(64),
                null, List.of("ruth"), "text/plain", 8L, Instant.now(), "corr-1", 0, 2, 3, ChangeType.UPSERT);

        String json = objectMapper.writeValueAsString(event);

        assertThat(json).contains("\"revision\":3");
        assertThat(json).contains("\"attachment_count\":2");
        assertThat(json).contains("\"change_type\":\"UPSERT\"");
    }
}
