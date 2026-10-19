package com.rnd.bridge.raw;

import com.google.protobuf.util.Timestamps;
import com.rnd.bridge.raw.model.ChangeType;
import com.rnd.bridge.raw.model.Classification;
import com.rnd.bridge.raw.model.DecidedBy;
import com.rnd.bridge.raw.model.Quality;
import com.rnd.bridge.raw.model.Modality;
import com.rnd.bridge.raw.model.RawObjectEvent;
import com.rnd.bridge.raw.model.RawObjectRef;
import com.rnd.bridge.raw.model.RawSourceType;
import com.rnd.bridge.raw.model.Role;
import com.rnd.bridge.raw.model.ScopeDecision;
import com.rnd.bridge.raw.model.Sensitivity;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Converts the domain {@link RawObjectEvent} record to the generated Protobuf message
 * published to Kafka. Protobuf stays a wire-format concern here - every reader, validator,
 * reconciler and MinIO call still operates on the domain record; only the publishers
 * (KafkaRawObjectPublisher) see the generated type, via this mapper.
 * <p>
 * The generated proto type shares enum simple names with their domain counterparts
 * (ScopeDecision, Modality, ChangeType, Role) and the message type itself shares
 * RawObjectEvent's name too, so proto references are fully qualified throughout rather than
 * imported, to avoid any ambiguity between com.rnd.bridge.raw.model.* (domain) and
 * com.rnd.bridge.shared.unstructured.dto.* (generated) types of the same simple name.
 * <p>
 * Proto enum values carry their enum's name as a prefix (Relay's convention -
 * shared/proto/raw-object-event.proto), so each mapping below is spelled out explicitly rather
 * than derived from name(): the exhaustive switches make adding a domain value without its
 * wire value a compile error. sourceRef and classification are optional on the wire - left
 * unset when the domain value is null.
 */
@Component
public class RawObjectEventProtoMapper {

    public com.rnd.bridge.shared.unstructured.dto.RawObjectEvent toProto(RawObjectEvent event) {
        com.rnd.bridge.shared.unstructured.dto.RawObjectEvent.Builder builder =
                com.rnd.bridge.shared.unstructured.dto.RawObjectEvent.newBuilder()
                        .setSchemaVersion(event.schemaVersion())
                        .setSourceType(toProtoSourceType(event.sourceType()))
                        .setTenantId(event.tenantId())
                        .setIngestedAt(toProtoTimestamp(event.ingestedAt()))
                        .setScopeDecision(toProtoScopeDecision(event.scopeDecision()))
                        .addAllParticipants(event.participants() == null ? List.of() : event.participants())
                        .setCorrelationId(event.correlationId())
                        .setRevision(event.revision())
                        .setChangeType(toProtoChangeType(event.changeType()))
                        .setChangeSignificanceScore(event.changeSignificanceScore())
                        .addAllObjects(event.objects().stream().map(this::toProtoRef).toList());

        if (event.sourceTimestamp() != null) {
            builder.setSourceTimestamp(toProtoTimestamp(event.sourceTimestamp()));
        }
        if (event.sourceRef() != null) {
            builder.setSourceRef(event.sourceRef());
        }
        if (event.classification() != null) {
            builder.setClassification(toProtoClassification(event.classification()));
        }

        return builder.build();
    }

    private com.rnd.bridge.shared.unstructured.dto.RawObjectRef toProtoRef(RawObjectRef ref) {
        com.rnd.bridge.shared.unstructured.dto.RawObjectRef.Builder builder =
                com.rnd.bridge.shared.unstructured.dto.RawObjectRef.newBuilder()
                        .setRole(toProtoRole(ref.role()))
                        .setObjectKey(ref.objectKey())
                        .setContentHash(ref.contentHash())
                        .setContentType(ref.contentType() == null ? "" : ref.contentType())
                        .setModality(toProtoModality(ref.modality()));
        if (ref.sizeBytes() != null) {
            builder.setSizeBytes(ref.sizeBytes());
        }
        return builder.build();
    }

    private static com.rnd.bridge.shared.unstructured.dto.Classification toProtoClassification(Classification classification) {
        return com.rnd.bridge.shared.unstructured.dto.Classification.newBuilder()
                .setDecidedBy(toProtoDecidedBy(classification.decidedBy()))
                .setCategory(com.rnd.bridge.shared.unstructured.dto.CategoryLabel.newBuilder()
                        .setValue(classification.category().value().name())
                        .setConfidence((float) classification.category().confidence()))
                .setQuality(com.rnd.bridge.shared.unstructured.dto.QualityLabel.newBuilder()
                        .setValue(toProtoQuality(classification.quality().value()))
                        .setConfidence((float) classification.quality().confidence()))
                .setSensitivity(com.rnd.bridge.shared.unstructured.dto.SensitivityLabel.newBuilder()
                        .setValue(toProtoSensitivity(classification.sensitivity().value()))
                        .setConfidence((float) classification.sensitivity().confidence()))
                .setReason(classification.reason())
                .setClassifier(com.rnd.bridge.shared.unstructured.dto.ClassifierInfo.newBuilder()
                        .setModel(classification.classifier().model())
                        .setPromptVersion(classification.classifier().promptVersion()))
                .build();
    }

    private static com.google.protobuf.Timestamp toProtoTimestamp(java.time.Instant instant) {
        return Timestamps.fromMillis(instant.toEpochMilli());
    }

    private static com.rnd.bridge.shared.unstructured.dto.SourceType toProtoSourceType(RawSourceType sourceType) {
        return switch (sourceType) {
            case EMAIL -> com.rnd.bridge.shared.unstructured.dto.SourceType.SOURCE_TYPE_EMAIL;
            case SLACK -> com.rnd.bridge.shared.unstructured.dto.SourceType.SOURCE_TYPE_SLACK;
            case WHATSAPP -> com.rnd.bridge.shared.unstructured.dto.SourceType.SOURCE_TYPE_WHATSAPP;
            case DRIVE -> com.rnd.bridge.shared.unstructured.dto.SourceType.SOURCE_TYPE_DRIVE;
            case CALENDAR -> com.rnd.bridge.shared.unstructured.dto.SourceType.SOURCE_TYPE_CALENDAR;
            case ZOOM -> com.rnd.bridge.shared.unstructured.dto.SourceType.SOURCE_TYPE_ZOOM;
            case BIGBLUEBUTTON -> com.rnd.bridge.shared.unstructured.dto.SourceType.SOURCE_TYPE_BIGBLUEBUTTON;
            case SCANNED_DOC -> com.rnd.bridge.shared.unstructured.dto.SourceType.SOURCE_TYPE_SCANNED_DOC;
        };
    }

    private static com.rnd.bridge.shared.unstructured.dto.ScopeDecision toProtoScopeDecision(
            ScopeDecision scopeDecision) {
        return switch (scopeDecision) {
            case INCLUDED -> com.rnd.bridge.shared.unstructured.dto.ScopeDecision.SCOPE_DECISION_INCLUDED;
            case EXCLUDED -> com.rnd.bridge.shared.unstructured.dto.ScopeDecision.SCOPE_DECISION_EXCLUDED;
        };
    }

    private static com.rnd.bridge.shared.unstructured.dto.Modality toProtoModality(Modality modality) {
        return switch (modality) {
            case TEXT -> com.rnd.bridge.shared.unstructured.dto.Modality.MODALITY_TEXT;
            case AUDIO -> com.rnd.bridge.shared.unstructured.dto.Modality.MODALITY_AUDIO;
            case SCANNED_DOC -> com.rnd.bridge.shared.unstructured.dto.Modality.MODALITY_SCANNED_DOC;
        };
    }

    private static com.rnd.bridge.shared.unstructured.dto.ChangeType toProtoChangeType(ChangeType changeType) {
        return switch (changeType) {
            case UPSERT -> com.rnd.bridge.shared.unstructured.dto.ChangeType.CHANGE_TYPE_UPSERT;
            case DELETE_REVIEW -> com.rnd.bridge.shared.unstructured.dto.ChangeType.CHANGE_TYPE_DELETE_REVIEW;
        };
    }

    private static com.rnd.bridge.shared.unstructured.dto.Role toProtoRole(Role role) {
        return switch (role) {
            case BODY -> com.rnd.bridge.shared.unstructured.dto.Role.ROLE_BODY;
            case ATTACHMENT -> com.rnd.bridge.shared.unstructured.dto.Role.ROLE_ATTACHMENT;
            case TRANSCRIPT -> com.rnd.bridge.shared.unstructured.dto.Role.ROLE_TRANSCRIPT;
            case RECORDING -> com.rnd.bridge.shared.unstructured.dto.Role.ROLE_RECORDING;
            case SOURCE_METADATA -> com.rnd.bridge.shared.unstructured.dto.Role.ROLE_SOURCE_METADATA;
        };
    }

    private static com.rnd.bridge.shared.unstructured.dto.DecidedBy toProtoDecidedBy(DecidedBy decidedBy) {
        return switch (decidedBy) {
            case MODEL -> com.rnd.bridge.shared.unstructured.dto.DecidedBy.DECIDED_BY_MODEL;
            case RULE -> com.rnd.bridge.shared.unstructured.dto.DecidedBy.DECIDED_BY_RULE;
        };
    }

    private static com.rnd.bridge.shared.unstructured.dto.Quality toProtoQuality(Quality quality) {
        return switch (quality) {
            case GOOD -> com.rnd.bridge.shared.unstructured.dto.Quality.QUALITY_GOOD;
            case POOR -> com.rnd.bridge.shared.unstructured.dto.Quality.QUALITY_POOR;
            case UNREADABLE -> com.rnd.bridge.shared.unstructured.dto.Quality.QUALITY_UNREADABLE;
        };
    }

    private static com.rnd.bridge.shared.unstructured.dto.Sensitivity toProtoSensitivity(Sensitivity sensitivity) {
        return switch (sensitivity) {
            case NONE -> com.rnd.bridge.shared.unstructured.dto.Sensitivity.SENSITIVITY_NONE;
            case LOW -> com.rnd.bridge.shared.unstructured.dto.Sensitivity.SENSITIVITY_LOW;
            case HIGH -> com.rnd.bridge.shared.unstructured.dto.Sensitivity.SENSITIVITY_HIGH;
        };
    }
}
