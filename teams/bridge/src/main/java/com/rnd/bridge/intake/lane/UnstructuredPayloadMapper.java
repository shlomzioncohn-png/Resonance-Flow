package com.rnd.bridge.intake.lane;

import com.rnd.bridge.core.PayloadMapper;
import com.rnd.bridge.core.error.LaneException;
import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.intake.service.IntakeLandingService;
import com.rnd.bridge.raw.model.Modality;
import com.rnd.bridge.raw.model.RawObjectEvent;
import com.rnd.bridge.raw.model.ScopeDecision;
import com.rnd.bridge.scoping.AllowlistScopingFilter;
import com.rnd.bridge.storage.ObjectKeyBuilder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;

/**
 * Maps a SourceRecord to the RawObjectEvent that gets published, carrying over
 * contentHash/modality derivation verbatim from the old PointerEventPublisher (no behavior
 * change). A pure transform - no I/O: contentType/sizeBytes are computed from the record
 * itself ({@link IntakeLandingService#resolveContentType} is a pure function, and sizeBytes
 * is just the raw content's length), so they don't need to wait on the actual MinIO put.
 * Landing the bytes is a separate side effect - see {@link UnstructuredIntakeLane#afterMap}.
 * Failures are wrapped in LaneException: AbstractIntakeLane's template method only catches
 * LaneException per record, so an unwrapped RuntimeException here would abort the whole run
 * instead of being recorded and skipped.
 */
final class UnstructuredPayloadMapper implements PayloadMapper<SourceRecord, RawObjectEvent> {

    private final AllowlistScopingFilter scopingFilter;
    private final ObjectKeyBuilder objectKeyBuilder;
    private final IntakeLandingService landingService;

    UnstructuredPayloadMapper(
            AllowlistScopingFilter scopingFilter,
            ObjectKeyBuilder objectKeyBuilder,
            IntakeLandingService landingService) {
        this.scopingFilter = scopingFilter;
        this.objectKeyBuilder = objectKeyBuilder;
        this.landingService = landingService;
    }

    @Override
    public RawObjectEvent map(SourceRecord record, Instant ingestedAt) {
        try {
            ScopeDecision scopeDecision = scopingFilter.decide(record);
            String objectKey = objectKeyBuilder.build(record, ingestedAt);
            String contentType = landingService.resolveContentType(record, objectKey);

            return RawObjectEvent.create(
                    objectKey,
                    record.sourceType(),
                    record.tenantId(),
                    ingestedAt,
                    scopeDecision,
                    computeContentHash(record),
                    deriveModality(record),
                    record.participants(),
                    contentType,
                    (long) record.rawContent().length,
                    record.occurredAt(),
                    record.correlationId(),
                    record.attachmentIndex());
        } catch (RuntimeException e) {
            throw new LaneException(
                    "Failed to map sourceType=" + record.sourceType() + " naturalId=" + record.naturalId(), e);
        }
    }

    private String computeContentHash(SourceRecord record) {
        byte[] content = record.rawContent();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content);
            StringBuilder hex = new StringBuilder("sha256:");
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private Modality deriveModality(SourceRecord record) {
        String mimeType = record.mimeType();
        if (mimeType == null) {
            return null;
        }
        if (mimeType.startsWith("audio/")) {
            return Modality.AUDIO;
        }
        if (mimeType.startsWith("image/") || mimeType.equals("application/pdf")) {
            return Modality.SCANNED_DOC;
        }
        return null;
    }
}
