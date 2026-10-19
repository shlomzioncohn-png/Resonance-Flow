package com.rnd.bridge.raw;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.util.Timestamps;
import com.rnd.bridge.raw.model.ChangeType;
import com.rnd.bridge.raw.model.Modality;
import com.rnd.bridge.raw.model.RawObjectEvent;
import com.rnd.bridge.raw.model.RawObjectRef;
import com.rnd.bridge.raw.model.RawSourceType;
import com.rnd.bridge.raw.model.Role;
import com.rnd.bridge.raw.model.ScopeDecision;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RawObjectEventProtoMapperTest {

    private final RawObjectEventProtoMapper mapper = new RawObjectEventProtoMapper();

    @Test
    void toProto_mapsEveryFieldWhenAllOptionalFieldsArePresent() {
        RawObjectRef body = new RawObjectRef(
                Role.BODY, "EMAIL/kamatech/2026-08-18/x.json", "sha256:" + "a".repeat(64),
                "application/json", 123L, Modality.AUDIO);
        RawObjectRef attachment = new RawObjectRef(
                Role.ATTACHMENT, "EMAIL/kamatech/2026-08-18/x.pdf", "sha256:" + "b".repeat(64),
                "application/pdf", 456L, Modality.SCANNED_DOC);
        RawObjectEvent event = RawObjectEvent.create(
                RawSourceType.EMAIL, "kamatech", Instant.parse("2026-08-18T09:15:00Z"), ScopeDecision.INCLUDED,
                List.of("hadas@kamatech.org"), Instant.parse("2026-08-18T09:20:00Z"), "corr-1", 3,
                ChangeType.DELETE_REVIEW, List.of(body, attachment));

        com.rnd.bridge.shared.unstructured.dto.RawObjectEvent proto = mapper.toProto(event);

        assertThat(proto.getSchemaVersion()).isEqualTo("1.0");
        assertThat(proto.getSourceType()).isEqualTo(com.rnd.bridge.shared.unstructured.dto.SourceType.SOURCE_TYPE_EMAIL);
        assertThat(proto.getTenantId()).isEqualTo("kamatech");
        assertThat(Timestamps.toMillis(proto.getIngestedAt()))
                .isEqualTo(Instant.parse("2026-08-18T09:15:00Z").toEpochMilli());
        assertThat(proto.getScopeDecision())
                .isEqualTo(com.rnd.bridge.shared.unstructured.dto.ScopeDecision.SCOPE_DECISION_INCLUDED);
        assertThat(proto.getParticipantsList()).containsExactly("hadas@kamatech.org");
        assertThat(proto.hasSourceTimestamp()).isTrue();
        assertThat(Timestamps.toMillis(proto.getSourceTimestamp()))
                .isEqualTo(Instant.parse("2026-08-18T09:20:00Z").toEpochMilli());
        assertThat(proto.getCorrelationId()).isEqualTo("corr-1");
        assertThat(proto.getRevision()).isEqualTo(3);
        assertThat(proto.getChangeType())
                .isEqualTo(com.rnd.bridge.shared.unstructured.dto.ChangeType.CHANGE_TYPE_DELETE_REVIEW);
        assertThat(proto.getChangeSignificanceScore()).isEqualTo(100);

        assertThat(proto.getObjectsList()).hasSize(2);
        com.rnd.bridge.shared.unstructured.dto.RawObjectRef protoBody = proto.getObjects(0);
        assertThat(protoBody.getRole()).isEqualTo(com.rnd.bridge.shared.unstructured.dto.Role.ROLE_BODY);
        assertThat(protoBody.getObjectKey()).isEqualTo("EMAIL/kamatech/2026-08-18/x.json");
        assertThat(protoBody.getContentHash()).isEqualTo("sha256:" + "a".repeat(64));
        assertThat(protoBody.getContentType()).isEqualTo("application/json");
        assertThat(protoBody.hasSizeBytes()).isTrue();
        assertThat(protoBody.getSizeBytes()).isEqualTo(123L);
        assertThat(protoBody.getModality()).isEqualTo(com.rnd.bridge.shared.unstructured.dto.Modality.MODALITY_AUDIO);

        com.rnd.bridge.shared.unstructured.dto.RawObjectRef protoAttachment = proto.getObjects(1);
        assertThat(protoAttachment.getRole()).isEqualTo(com.rnd.bridge.shared.unstructured.dto.Role.ROLE_ATTACHMENT);
    }

    @Test
    void toProto_omitsOptionalFieldsWhenAbsentFromTheDomainRecord() {
        RawObjectRef ref = new RawObjectRef(
                Role.TRANSCRIPT, "SLACK/kamatech/2026-08-18/x.json", "sha256:" + "b".repeat(64), null, null,
                Modality.TEXT);
        RawObjectEvent event = RawObjectEvent.create(
                RawSourceType.SLACK, "kamatech", Instant.parse("2026-08-18T09:15:00Z"), ScopeDecision.INCLUDED,
                null, null, "corr-2", 0, ChangeType.UPSERT, List.of(ref));

        com.rnd.bridge.shared.unstructured.dto.RawObjectEvent proto = mapper.toProto(event);

        assertThat(proto.getParticipantsList()).isEmpty();
        assertThat(proto.hasSourceTimestamp()).isFalse();
        assertThat(proto.getObjects(0).getContentType()).isEmpty();
        assertThat(proto.getObjects(0).hasSizeBytes()).isFalse();
        assertThat(proto.getObjects(0).getModality()).isEqualTo(com.rnd.bridge.shared.unstructured.dto.Modality.MODALITY_TEXT);
    }

    @Test
    void toProto_mapsMediaSourceTypesToTheirPrefixedWireValues() {
        RawObjectRef ref = new RawObjectRef(
                Role.BODY, "SCANNED_DOC/kamatech/2026-08-18/x.json", "sha256:" + "c".repeat(64), null, null,
                Modality.SCANNED_DOC);

        assertThat(mapper.toProto(event(RawSourceType.SCANNED_DOC, ref)).getSourceType())
                .isEqualTo(com.rnd.bridge.shared.unstructured.dto.SourceType.SOURCE_TYPE_SCANNED_DOC);
        assertThat(mapper.toProto(event(RawSourceType.ZOOM, ref)).getSourceType())
                .isEqualTo(com.rnd.bridge.shared.unstructured.dto.SourceType.SOURCE_TYPE_ZOOM);
        assertThat(mapper.toProto(event(RawSourceType.BIGBLUEBUTTON, ref)).getSourceType())
                .isEqualTo(com.rnd.bridge.shared.unstructured.dto.SourceType.SOURCE_TYPE_BIGBLUEBUTTON);
    }

    @Test
    void toProto_leavesSourceRefAndClassificationUnsetWhenAbsent() {
        RawObjectRef ref = new RawObjectRef(
                Role.BODY, "EMAIL/kamatech/2026-08-18/x.json", "sha256:" + "d".repeat(64), "text/plain", 1L,
                Modality.TEXT);

        com.rnd.bridge.shared.unstructured.dto.RawObjectEvent proto = mapper.toProto(event(RawSourceType.EMAIL, ref));

        assertThat(proto.hasSourceRef()).isFalse();
        assertThat(proto.hasClassification()).isFalse();
    }

    @Test
    void toProto_mapsSourceRefAndClassification() {
        RawObjectRef ref = new RawObjectRef(
                Role.BODY, "SCANNED_DOC/kamatech/2026-08-18/x.pdf", "sha256:" + "e".repeat(64),
                "application/pdf", 10L, Modality.SCANNED_DOC);
        RawObjectEvent event = event(RawSourceType.SCANNED_DOC, ref)
                .withSourceRef("rec-1")
                .withClassification(new com.rnd.bridge.raw.model.Classification(
                        com.rnd.bridge.raw.model.DecidedBy.MODEL,
                        new com.rnd.bridge.raw.model.Label<>(com.rnd.bridge.raw.model.DocumentCategory.LETTER, 0.95),
                        new com.rnd.bridge.raw.model.Label<>(com.rnd.bridge.raw.model.Quality.GOOD, 0.9),
                        new com.rnd.bridge.raw.model.Label<>(com.rnd.bridge.raw.model.Sensitivity.HIGH, 0.8),
                        "employee termination letter",
                        new com.rnd.bridge.raw.model.ClassifierInfo("gemini-3.8-flash", "v2")));

        com.rnd.bridge.shared.unstructured.dto.RawObjectEvent proto = mapper.toProto(event);

        assertThat(proto.getSourceRef()).isEqualTo("rec-1");
        com.rnd.bridge.shared.unstructured.dto.Classification c = proto.getClassification();
        assertThat(c.getDecidedBy()).isEqualTo(com.rnd.bridge.shared.unstructured.dto.DecidedBy.DECIDED_BY_MODEL);
        assertThat(c.getCategory().getValue()).isEqualTo("LETTER");
        assertThat(c.getCategory().getConfidence()).isEqualTo(0.95f);
        assertThat(c.getQuality().getValue()).isEqualTo(com.rnd.bridge.shared.unstructured.dto.Quality.QUALITY_GOOD);
        assertThat(c.getSensitivity().getValue()).isEqualTo(com.rnd.bridge.shared.unstructured.dto.Sensitivity.SENSITIVITY_HIGH);
        assertThat(c.getReason()).isEqualTo("employee termination letter");
        assertThat(c.getClassifier().getModel()).isEqualTo("gemini-3.8-flash");
        assertThat(c.getClassifier().getPromptVersion()).isEqualTo("v2");
    }

    private static RawObjectEvent event(RawSourceType sourceType, RawObjectRef ref) {
        return RawObjectEvent.create(
                sourceType, "kamatech", Instant.now(), ScopeDecision.INCLUDED, List.of(),
                null, "corr-3", 0, ChangeType.UPSERT, List.of(ref));
    }
}
