package com.rnd.bridge.shared.unstructured.dto;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.time.Instant;
import java.util.List;

/**
 * Published to Kafka topic bridge.raw.landed by both unstructured lanes (Malki and
 * Esther). Matches the official RawObjectEvent JSON Schema (additionalProperties: false)
 * exactly — field names are camelCase in Java but serialize as snake_case on the wire via
 * @JsonNaming, and content_hash's format ("sha256:<64 hex>") and object_key's pattern
 * ("[A-Z_]+/tenant/yyyy-MM-dd/file") are schema-enforced. traceId and language from an
 * earlier schema draft are gone — the current schema has no such fields, and
 * additionalProperties:false forbids adding them back.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RawObjectEvent(
        String schemaVersion,
        String objectKey,
        SourceType sourceType,
        String tenantId,
        Instant ingestedAt,
        ScopeDecision scopeDecision,
        String contentHash,
        Modality modality,
        List<String> participants,
        String contentType,
        Long sizeBytes,
        String transcriptObjectKey,
        Instant sourceTimestamp) {

    private static final String CURRENT_SCHEMA_VERSION = "1.0";

    public static RawObjectEvent create(
            String objectKey,
            SourceType sourceType,
            String tenantId,
            Instant ingestedAt,
            ScopeDecision scopeDecision,
            String contentHash,
            Modality modality,
            List<String> participants,
            String contentType,
            Long sizeBytes,
            Instant sourceTimestamp) {
        return new RawObjectEvent(
                CURRENT_SCHEMA_VERSION, objectKey, sourceType, tenantId, ingestedAt,
                scopeDecision, contentHash, modality, participants, contentType, sizeBytes,
                null, sourceTimestamp);
    }
}
