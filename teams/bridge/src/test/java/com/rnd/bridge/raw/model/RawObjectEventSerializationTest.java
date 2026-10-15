package com.rnd.bridge.raw.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Proves the wire format actually matches the agreed schema's snake_case field names -
 * not just that the Java record compiles, but that @JsonNaming really produces
 * source_type/correlation_id/etc. (and, on each nested object, object_key/content_hash/etc.)
 * on the JSON that gets published to Kafka.
 */
class RawObjectEventSerializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    private static RawObjectRef ref(Role role) {
        return new RawObjectRef(role, "EMAIL/kamatech/2026-08-18/x.json", "sha256:" + "a".repeat(64),
                "application/json", 123L, Modality.AUDIO);
    }

    @Test
    void serializesFieldNamesAsSnakeCase() throws Exception {
        RawObjectEvent event = RawObjectEvent.create(
                RawSourceType.EMAIL,
                "kamatech",
                Instant.parse("2026-08-18T09:15:00Z"),
                ScopeDecision.INCLUDED,
                List.of("hadas@kamatech.org"),
                Instant.parse("2026-08-18T09:15:00Z"),
                "corr-1",
                0,
                ChangeType.UPSERT,
                List.of(ref(Role.BODY)));

        String json = objectMapper.writeValueAsString(event);

        assertThat(json).contains("\"schema_version\"");
        assertThat(json).contains("\"source_type\"");
        assertThat(json).contains("\"tenant_id\"");
        assertThat(json).contains("\"ingested_at\"");
        assertThat(json).contains("\"scope_decision\"");
        assertThat(json).contains("\"source_timestamp\"");
        assertThat(json).contains("\"correlation_id\"");
        assertThat(json).contains("\"revision\"");
        assertThat(json).contains("\"change_type\"");
        assertThat(json).contains("\"change_significance_score\"");
        assertThat(json).contains("\"objects\"");
        assertThat(json).contains("\"object_key\"");
        assertThat(json).contains("\"content_hash\"");
        assertThat(json).contains("\"content_type\"");
        assertThat(json).contains("\"size_bytes\"");

        assertThat(json).doesNotContain("\"sourceType\"");
        assertThat(json).doesNotContain("\"changeType\"");
        assertThat(json).doesNotContain("\"changeSignificanceScore\"");
        assertThat(json).doesNotContain("\"objectKey\"");
        assertThat(json).doesNotContain("\"contentHash\"");
    }

    @Test
    void serializesChangeSignificanceScoreAsTheCurrentHardcodedValue() throws Exception {
        RawObjectEvent event = RawObjectEvent.create(
                RawSourceType.EMAIL, "kamatech", Instant.now(), ScopeDecision.INCLUDED, List.of(),
                Instant.now(), "corr-1", 0, ChangeType.UPSERT, List.of(ref(Role.BODY)));

        String json = objectMapper.writeValueAsString(event);

        assertThat(json).contains("\"change_significance_score\":100");
        assertThat(event.changeSignificanceScore()).isEqualTo(100);
    }

    @Test
    void serializesSchemaVersionAsTheCurrentBreakingContractVersion() throws Exception {
        RawObjectEvent event = RawObjectEvent.create(
                RawSourceType.EMAIL, "kamatech", Instant.now(), ScopeDecision.INCLUDED, List.of(),
                Instant.now(), "corr-1", 0, ChangeType.UPSERT, List.of(ref(Role.BODY)));

        // Still "1.0" during active development - the 2.0 breaking-contract bump is deliberately
        // deferred until this shape is actually finalized, not made here.
        assertThat(event.schemaVersion()).isEqualTo("1.0");
    }

    @Test
    void serializesModalityAndRoleValuesUppercaseOnEachObject() throws Exception {
        RawObjectEvent event = RawObjectEvent.create(
                RawSourceType.EMAIL, "kamatech", Instant.now(), ScopeDecision.INCLUDED, List.of(),
                Instant.now(), "corr-1", 0, ChangeType.UPSERT,
                List.of(new RawObjectRef(Role.ATTACHMENT, "EMAIL/kamatech/2026-08-18/x.pdf",
                        "sha256:" + "a".repeat(64), "application/pdf", 10L, Modality.SCANNED_DOC)));

        String json = objectMapper.writeValueAsString(event);

        assertThat(json).contains("\"modality\":\"SCANNED_DOC\"");
        assertThat(json).contains("\"role\":\"ATTACHMENT\"");
    }

    @Test
    void serializesSourceTypeAndScopeDecisionValuesUppercase() throws Exception {
        RawObjectEvent event = RawObjectEvent.create(
                RawSourceType.EMAIL, "kamatech", Instant.now(), ScopeDecision.INCLUDED, List.of(),
                Instant.now(), "corr-1", 0, ChangeType.UPSERT, List.of(ref(Role.BODY)));

        String json = objectMapper.writeValueAsString(event);

        assertThat(json).contains("\"source_type\":\"EMAIL\"");
        assertThat(json).contains("\"scope_decision\":\"INCLUDED\"");
    }

    @Test
    void serializesRevisionAndChangeTypeAsSnakeCaseWithUppercaseEnumValue() throws Exception {
        RawObjectEvent event = RawObjectEvent.create(
                RawSourceType.SLACK, "kamatech", Instant.now(), ScopeDecision.INCLUDED, List.of("ruth"),
                Instant.now(), "corr-1", 3, ChangeType.UPSERT, List.of(ref(Role.TRANSCRIPT)));

        String json = objectMapper.writeValueAsString(event);

        assertThat(json).contains("\"revision\":3");
        assertThat(json).contains("\"change_type\":\"UPSERT\"");
    }

    @Test
    void objectsCarriesEveryObjectBelongingToTheLogicalItem() throws Exception {
        RawObjectEvent event = RawObjectEvent.create(
                RawSourceType.SLACK, "kamatech", Instant.now(), ScopeDecision.INCLUDED, List.of("ruth"),
                Instant.now(), "corr-1", 0, ChangeType.UPSERT,
                List.of(ref(Role.TRANSCRIPT), ref(Role.ATTACHMENT)));

        assertThat(event.objects()).hasSize(2);
        assertThat(event.objects().get(0).role()).isEqualTo(Role.TRANSCRIPT);
        assertThat(event.objects().get(1).role()).isEqualTo(Role.ATTACHMENT);
    }
}
