package com.rnd.bridge.raw.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Proves the wire format actually matches the official schema's snake_case field names —
 * not just that the Java record compiles, but that @JsonNaming really produces
 * correlation_id/content_hash/etc. on the JSON that gets published to Kafka.
 */
class RawObjectEventSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void serializesFieldNamesAsSnakeCase() throws Exception {
        RawObjectRef recording = new RawObjectRef(
                Role.RECORDING, "EMAIL/kamatech/2026-08-18/x.json", "sha256:" + "a".repeat(64),
                "application/json", 123L, Modality.AUDIO);

        RawObjectEvent event = RawObjectEvent.create(
                RawSourceType.EMAIL,
                "kamatech",
                "corr-1",
                Instant.parse("2026-08-18T09:15:00Z"),
                ScopeDecision.INCLUDED,
                0,
                ChangeType.UPSERT,
                100,
                List.of("hadas@kamatech.org"),
                Instant.parse("2026-08-18T09:15:00Z"),
                "msg-ref-1",
                List.of(recording));

        String json = objectMapper.writeValueAsString(event);

        assertThat(json).contains("\"source_type\"");
        assertThat(json).contains("\"tenant_id\"");
        assertThat(json).contains("\"correlation_id\"");
        assertThat(json).contains("\"ingested_at\"");
        assertThat(json).contains("\"scope_decision\"");
        assertThat(json).contains("\"schema_version\"");
        assertThat(json).contains("\"change_type\"");
        assertThat(json).contains("\"change_significance_score\"");
        assertThat(json).contains("\"source_timestamp\"");
        assertThat(json).contains("\"source_ref\"");
        assertThat(json).contains("\"objects\"");
        assertThat(json).contains("\"object_key\"");
        assertThat(json).contains("\"content_hash\"");
        assertThat(json).contains("\"content_type\"");
        assertThat(json).contains("\"size_bytes\"");
        assertThat(json).contains("\"role\"");

        assertThat(json).doesNotContain("\"correlationId\"");
        assertThat(json).doesNotContain("\"sourceType\"");
        assertThat(json).doesNotContain("\"objectKey\"");
    }

    @Test
    void serializesModalityValuesLowercase() throws Exception {
        RawObjectRef object = new RawObjectRef(
                Role.BODY, "EMAIL/kamatech/2026-08-18/x.json", "sha256:" + "a".repeat(64),
                null, null, Modality.SCANNED_DOC);

        RawObjectEvent event = RawObjectEvent.create(
                RawSourceType.EMAIL, "kamatech", "corr-1", Instant.now(), ScopeDecision.INCLUDED,
                0, ChangeType.UPSERT, 100, List.of(), Instant.now(), null, List.of(object));

        String json = objectMapper.writeValueAsString(event);

        assertThat(json).contains("\"modality\":\"scanned_doc\"");
    }

    @Test
    void dedupKeyIsCorrelationIdNotAContentHash() {
        RawObjectRef object = new RawObjectRef(
                Role.BODY, "EMAIL/kamatech/2026-08-18/x.json", "sha256:" + "a".repeat(64),
                "application/json", 10L, null);

        RawObjectEvent event = RawObjectEvent.create(
                RawSourceType.EMAIL, "kamatech", "corr-42", Instant.now(), ScopeDecision.INCLUDED,
                0, ChangeType.UPSERT, 100, List.of(), Instant.now(), null, List.of(object));

        assertThat(event.dedupKey()).isEqualTo("corr-42");
    }
}
