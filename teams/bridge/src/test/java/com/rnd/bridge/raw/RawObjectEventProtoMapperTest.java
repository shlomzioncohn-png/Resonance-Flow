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
        assertThat(proto.getSourceType()).isEqualTo(com.rnd.bridge.shared.unstructured.dto.SourceType.EMAIL);
        assertThat(proto.getTenantId()).isEqualTo("kamatech");
        assertThat(Timestamps.toMillis(proto.getIngestedAt()))
                .isEqualTo(Instant.parse("2026-08-18T09:15:00Z").toEpochMilli());
        assertThat(proto.getScopeDecision())
                .isEqualTo(com.rnd.bridge.shared.unstructured.dto.ScopeDecision.INCLUDED);
        assertThat(proto.getParticipantsList()).containsExactly("hadas@kamatech.org");
        assertThat(proto.hasSourceTimestamp()).isTrue();
        assertThat(Timestamps.toMillis(proto.getSourceTimestamp()))
                .isEqualTo(Instant.parse("2026-08-18T09:20:00Z").toEpochMilli());
        assertThat(proto.getCorrelationId()).isEqualTo("corr-1");
        assertThat(proto.getRevision()).isEqualTo(3);
        assertThat(proto.getChangeType())
                .isEqualTo(com.rnd.bridge.shared.unstructured.dto.ChangeType.DELETE_REVIEW);
        assertThat(proto.getChangeSignificanceScore()).isEqualTo(100);

        assertThat(proto.getObjectsList()).hasSize(2);
        com.rnd.bridge.shared.unstructured.dto.RawObjectRef protoBody = proto.getObjects(0);
        assertThat(protoBody.getRole()).isEqualTo(com.rnd.bridge.shared.unstructured.dto.Role.BODY);
        assertThat(protoBody.getObjectKey()).isEqualTo("EMAIL/kamatech/2026-08-18/x.json");
        assertThat(protoBody.getContentHash()).isEqualTo("sha256:" + "a".repeat(64));
        assertThat(protoBody.getContentType()).isEqualTo("application/json");
        assertThat(protoBody.hasSizeBytes()).isTrue();
        assertThat(protoBody.getSizeBytes()).isEqualTo(123L);
        assertThat(protoBody.getModality()).isEqualTo(com.rnd.bridge.shared.unstructured.dto.Modality.AUDIO);

        com.rnd.bridge.shared.unstructured.dto.RawObjectRef protoAttachment = proto.getObjects(1);
        assertThat(protoAttachment.getRole()).isEqualTo(com.rnd.bridge.shared.unstructured.dto.Role.ATTACHMENT);
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
        assertThat(proto.getObjects(0).getModality()).isEqualTo(com.rnd.bridge.shared.unstructured.dto.Modality.TEXT);
    }

    @Test
    void toProto_mapsScannedDocAndDocSourceTypes() {
        RawObjectRef ref = new RawObjectRef(
                Role.BODY, "SCANNED_DOC/kamatech/2026-08-18/x.json", "sha256:" + "c".repeat(64), null, null,
                Modality.SCANNED_DOC);
        RawObjectEvent scannedDoc = RawObjectEvent.create(
                RawSourceType.SCANNED_DOC, "kamatech", Instant.now(), ScopeDecision.INCLUDED, List.of(),
                null, "corr-3", 0, ChangeType.UPSERT, List.of(ref));
        RawObjectEvent doc = RawObjectEvent.create(
                RawSourceType.DOC, "kamatech", Instant.now(), ScopeDecision.INCLUDED, List.of(),
                null, "corr-4", 0, ChangeType.UPSERT, List.of(ref));

        assertThat(mapper.toProto(scannedDoc).getSourceType())
                .isEqualTo(com.rnd.bridge.shared.unstructured.dto.SourceType.SOURCE_TYPE_SCANNED_DOC);
        assertThat(mapper.toProto(doc).getSourceType())
                .isEqualTo(com.rnd.bridge.shared.unstructured.dto.SourceType.DOC);
    }
}
