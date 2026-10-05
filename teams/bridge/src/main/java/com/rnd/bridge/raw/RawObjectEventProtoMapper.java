package com.rnd.bridge.raw;

import com.google.protobuf.util.Timestamps;
import com.rnd.bridge.raw.model.ChangeType;
import com.rnd.bridge.raw.model.Modality;
import com.rnd.bridge.raw.model.RawObjectEvent;
import com.rnd.bridge.raw.model.RawSourceType;
import com.rnd.bridge.raw.model.ScopeDecision;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Converts the domain {@link RawObjectEvent} record to the generated Protobuf message
 * published to Kafka. Protobuf stays a wire-format concern here - every reader, validator,
 * reconciler and MinIO call still operates on the domain record; only the publishers
 * (KafkaRawObjectPublisher) see the generated type, via this mapper.
 * <p>
 * The generated proto type shares three enum simple names with their domain counterparts
 * (ScopeDecision, Modality, ChangeType) and the message type itself shares RawObjectEvent's
 * name too, so proto references are fully qualified throughout rather than imported, to avoid
 * any ambiguity between com.rnd.bridge.raw.model.* (domain) and
 * com.rnd.bridge.shared.unstructured.dto.* (generated) types of the same simple name.
 */
@Component
public class RawObjectEventProtoMapper {

    public com.rnd.bridge.shared.unstructured.dto.RawObjectEvent toProto(RawObjectEvent event) {
        com.rnd.bridge.shared.unstructured.dto.RawObjectEvent.Builder builder =
                com.rnd.bridge.shared.unstructured.dto.RawObjectEvent.newBuilder()
                        .setSchemaVersion(event.schemaVersion())
                        .setObjectKey(event.objectKey())
                        .setSourceType(toProtoSourceType(event.sourceType()))
                        .setTenantId(event.tenantId())
                        .setIngestedAt(toProtoTimestamp(event.ingestedAt()))
                        .setScopeDecision(toProtoScopeDecision(event.scopeDecision()))
                        .setContentHash(event.contentHash())
                        .setModality(toProtoModality(event.modality()))
                        .addAllParticipants(event.participants() == null ? List.of() : event.participants())
                        .setContentType(event.contentType() == null ? "" : event.contentType())
                        .setCorrelationId(event.correlationId())
                        .setAttachmentIndex(event.attachmentIndex())
                        .setAttachmentCount(event.attachmentCount())
                        .setRevision(event.revision())
                        .setChangeType(toProtoChangeType(event.changeType()))
                        .setChangeSignificanceScore(event.changeSignificanceScore());

        if (event.sizeBytes() != null) {
            builder.setSizeBytes(event.sizeBytes());
        }
        if (event.transcriptObjectKey() != null) {
            builder.setTranscriptObjectKey(event.transcriptObjectKey());
        }
        if (event.sourceTimestamp() != null) {
            builder.setSourceTimestamp(toProtoTimestamp(event.sourceTimestamp()));
        }

        return builder.build();
    }

    private static com.google.protobuf.Timestamp toProtoTimestamp(java.time.Instant instant) {
        return Timestamps.fromMillis(instant.toEpochMilli());
    }

    private static com.rnd.bridge.shared.unstructured.dto.SourceType toProtoSourceType(RawSourceType sourceType) {
        return switch (sourceType) {
            case EMAIL -> com.rnd.bridge.shared.unstructured.dto.SourceType.EMAIL;
            case SLACK -> com.rnd.bridge.shared.unstructured.dto.SourceType.SLACK;
            case WHATSAPP -> com.rnd.bridge.shared.unstructured.dto.SourceType.WHATSAPP;
            case DRIVE -> com.rnd.bridge.shared.unstructured.dto.SourceType.DRIVE;
            case CALENDAR -> com.rnd.bridge.shared.unstructured.dto.SourceType.CALENDAR;
            case MEETING -> com.rnd.bridge.shared.unstructured.dto.SourceType.MEETING;
            case SCANNED_DOC -> com.rnd.bridge.shared.unstructured.dto.SourceType.SOURCE_TYPE_SCANNED_DOC;
            case DOC -> com.rnd.bridge.shared.unstructured.dto.SourceType.DOC;
        };
    }

    private static com.rnd.bridge.shared.unstructured.dto.ScopeDecision toProtoScopeDecision(
            ScopeDecision scopeDecision) {
        return switch (scopeDecision) {
            case INCLUDED -> com.rnd.bridge.shared.unstructured.dto.ScopeDecision.INCLUDED;
            case EXCLUDED -> com.rnd.bridge.shared.unstructured.dto.ScopeDecision.EXCLUDED;
        };
    }

    private static com.rnd.bridge.shared.unstructured.dto.Modality toProtoModality(Modality modality) {
        return switch (modality) {
            case TEXT -> com.rnd.bridge.shared.unstructured.dto.Modality.TEXT;
            case AUDIO -> com.rnd.bridge.shared.unstructured.dto.Modality.AUDIO;
            case SCANNED_DOC -> com.rnd.bridge.shared.unstructured.dto.Modality.SCANNED_DOC;
        };
    }

    private static com.rnd.bridge.shared.unstructured.dto.ChangeType toProtoChangeType(ChangeType changeType) {
        return switch (changeType) {
            case UPSERT -> com.rnd.bridge.shared.unstructured.dto.ChangeType.UPSERT;
            case DELETE_REVIEW -> com.rnd.bridge.shared.unstructured.dto.ChangeType.DELETE_REVIEW;
        };
    }
}
