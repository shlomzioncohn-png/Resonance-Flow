package com.rnd.bridge.raw;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.protobuf.util.Timestamps;
import com.rnd.bridge.raw.model.ChangeType;
import com.rnd.bridge.raw.model.Modality;
import com.rnd.bridge.raw.model.RawObjectEvent;
import com.rnd.bridge.raw.model.RawSourceType;
import com.rnd.bridge.raw.model.ScopeDecision;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RawObjectEventProtoMapperTest {

    private final RawObjectEventProtoMapper mapper = new RawObjectEventProtoMapper();

    @Test
    void toProto_mapsEveryFieldWhenAllOptionalFieldsArePresent() {
        RawObjectEvent event = RawObjectEvent.create(
                "EMAIL/kamatech/2026-08-18/x.json", RawSourceType.EMAIL, "kamatech",
                Instant.parse("2026-08-18T09:15:00Z"), ScopeDecision.INCLUDED,
                "sha256:" + "a".repeat(64), Modality.AUDIO, List.of("hadas@kamatech.org"),
                "application/json", 123L, "EMAIL/kamatech/2026-08-18/x.txt",
                Instant.parse("2026-08-18T09:20:00Z"), "corr-1", 1, 2, 3, ChangeType.DELETE_REVIEW);

        com.rnd.bridge.shared.unstructured.dto.RawObjectEvent proto = mapper.toProto(event);

        assertThat(proto.getSchemaVersion()).isEqualTo("1.0");
        assertThat(proto.getObjectKey()).isEqualTo("EMAIL/kamatech/2026-08-18/x.json");
        assertThat(proto.getSourceType()).isEqualTo(com.rnd.bridge.shared.unstructured.dto.SourceType.EMAIL);
        assertThat(proto.getTenantId()).isEqualTo("kamatech");
        assertThat(Timestamps.toMillis(proto.getIngestedAt()))
                .isEqualTo(Instant.parse("2026-08-18T09:15:00Z").toEpochMilli());
        assertThat(proto.getScopeDecision())
                .isEqualTo(com.rnd.bridge.shared.unstructured.dto.ScopeDecision.INCLUDED);
        assertThat(proto.getContentHash()).isEqualTo("sha256:" + "a".repeat(64));
        assertThat(proto.getModality()).isEqualTo(com.rnd.bridge.shared.unstructured.dto.Modality.AUDIO);
        assertThat(proto.getParticipantsList()).containsExactly("hadas@kamatech.org");
        assertThat(proto.getContentType()).isEqualTo("application/json");
        assertThat(proto.hasSizeBytes()).isTrue();
        assertThat(proto.getSizeBytes()).isEqualTo(123L);
        assertThat(proto.hasTranscriptObjectKey()).isTrue();
        assertThat(proto.getTranscriptObjectKey()).isEqualTo("EMAIL/kamatech/2026-08-18/x.txt");
        assertThat(proto.hasSourceTimestamp()).isTrue();
        assertThat(Timestamps.toMillis(proto.getSourceTimestamp()))
                .isEqualTo(Instant.parse("2026-08-18T09:20:00Z").toEpochMilli());
        assertThat(proto.getCorrelationId()).isEqualTo("corr-1");
        assertThat(proto.getAttachmentIndex()).isEqualTo(1);
        assertThat(proto.getAttachmentCount()).isEqualTo(2);
        assertThat(proto.getRevision()).isEqualTo(3);
        assertThat(proto.getChangeType())
                .isEqualTo(com.rnd.bridge.shared.unstructured.dto.ChangeType.DELETE_REVIEW);
        assertThat(proto.getChangeSignificanceScore()).isEqualTo(100);
    }

    @Test
    void toProto_omitsOptionalFieldsWhenAbsentFromTheDomainRecord() {
        RawObjectEvent event = RawObjectEvent.create(
                "SLACK/kamatech/2026-08-18/x.json", RawSourceType.SLACK, "kamatech",
                Instant.parse("2026-08-18T09:15:00Z"), ScopeDecision.INCLUDED,
                "sha256:" + "b".repeat(64), Modality.TEXT, null, null, null,
                null, "corr-2", 0, 0, 0, ChangeType.UPSERT);

        com.rnd.bridge.shared.unstructured.dto.RawObjectEvent proto = mapper.toProto(event);

        assertThat(proto.getParticipantsList()).isEmpty();
        assertThat(proto.getContentType()).isEmpty();
        assertThat(proto.hasSizeBytes()).isFalse();
        assertThat(proto.hasTranscriptObjectKey()).isFalse();
        assertThat(proto.hasSourceTimestamp()).isFalse();
        assertThat(proto.getModality()).isEqualTo(com.rnd.bridge.shared.unstructured.dto.Modality.TEXT);
    }

    @Test
    void toProto_mapsScannedDocAndDocSourceTypes() {
        RawObjectEvent scannedDoc = RawObjectEvent.create(
                "SCANNED_DOC/kamatech/2026-08-18/x.json", RawSourceType.SCANNED_DOC, "kamatech",
                Instant.now(), ScopeDecision.INCLUDED, "sha256:" + "c".repeat(64), Modality.SCANNED_DOC,
                List.of(), null, null, null, "corr-3", 0, 0, 0, ChangeType.UPSERT);
        RawObjectEvent doc = RawObjectEvent.create(
                "DOC/kamatech/2026-08-18/x.json", RawSourceType.DOC, "kamatech",
                Instant.now(), ScopeDecision.INCLUDED, "sha256:" + "d".repeat(64), Modality.TEXT,
                List.of(), null, null, null, "corr-4", 0, 0, 0, ChangeType.UPSERT);

        assertThat(mapper.toProto(scannedDoc).getSourceType())
                .isEqualTo(com.rnd.bridge.shared.unstructured.dto.SourceType.SOURCE_TYPE_SCANNED_DOC);
        assertThat(mapper.toProto(doc).getSourceType())
                .isEqualTo(com.rnd.bridge.shared.unstructured.dto.SourceType.DOC);
    }
}
