package com.rnd.bridge.intake.unstructured.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Published to Kafka topic bridge.raw.landed. Matches the official RawObjectEvent JSON
 * Schema (additionalProperties: false) exactly — field names, required set, and formats
 * (dedupKey: "sha256:<64 hex>", objectKey: "[A-Z_]+/tenant/yyyy-MM-dd/file") are all
 * schema-enforced. language is intentionally omitted (optional in the schema; real
 * language detection is out of scope for this skeleton).
 */
public record RawObjectEvent(
        String schemaVersion,
        String objectKey,
        SourceType sourceType,
        String tenantId,
        Instant ingestedAt,
        ScopeDecision scopeDecision,
        String dedupKey,
        Modality modality,
        UUID traceId,
        String contentType,
        Long sizeBytes,
        Instant sourceTimestamp) {

    private static final String CURRENT_SCHEMA_VERSION = "1.0";

    public static RawObjectEvent create(
            String objectKey,
            SourceType sourceType,
            String tenantId,
            Instant ingestedAt,
            ScopeDecision scopeDecision,
            String dedupKey,
            Modality modality,
            UUID traceId,
            String contentType,
            Long sizeBytes,
            Instant sourceTimestamp) {
        return new RawObjectEvent(
                CURRENT_SCHEMA_VERSION, objectKey, sourceType, tenantId, ingestedAt,
                scopeDecision, dedupKey, modality, traceId, contentType, sizeBytes, sourceTimestamp);
    }
}
