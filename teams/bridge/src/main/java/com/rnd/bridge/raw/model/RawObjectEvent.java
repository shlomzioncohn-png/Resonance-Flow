package com.rnd.bridge.raw.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.rnd.bridge.core.BridgePayload;
import java.time.Instant;
import java.util.List;

/**
 * Published to Kafka topic bridge.raw.landed by both unstructured lanes (Malki and
 * Esther). Matches the official RawObjectEvent JSON Schema (additionalProperties: false)
 * exactly — field names are camelCase in Java but serialize as snake_case on the wire via
 * @JsonNaming, and content_hash's format ("sha256:<64 hex>") and object_key's pattern
 * ("[A-Z_]+/tenant/yyyy-MM-dd/file") are schema-enforced. traceId and language from an
 * earlier schema draft are gone — the current schema has no such fields.
 *
 * correlationId/attachmentIndex are a Bridge-proposed addition, confirmed by Mind: they
 * let a body event and its attachment event(s), split from the same logical source item,
 * be linked without Mind having to parse object_key. Nullable so a producer that never
 * splits items (or a consumer that doesn't care) is unaffected.
 *
 * implements BridgePayload (see core.BridgePayload): tenantId() is satisfied by the
 * record's own tenantId component; dedupKey() is contentHash — the object_key is always
 * unique per landed instance (random uuid), so it can't detect re-landed identical
 * content the way contentHash does.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RawObjectEvent(
        String schemaVersion,
        String objectKey,
        RawSourceType sourceType,
        String tenantId,
        Instant ingestedAt,
        ScopeDecision scopeDecision,
        String contentHash,
        Modality modality,
        List<String> participants,
        String contentType,
        Long sizeBytes,
        String transcriptObjectKey,
        Instant sourceTimestamp,
        String correlationId,
        Integer attachmentIndex) implements BridgePayload {

    private static final String CURRENT_SCHEMA_VERSION = "1.0";

    public static RawObjectEvent create(
            String objectKey,
            RawSourceType sourceType,
            String tenantId,
            Instant ingestedAt,
            ScopeDecision scopeDecision,
            String contentHash,
            Modality modality,
            List<String> participants,
            String contentType,
            Long sizeBytes,
            Instant sourceTimestamp,
            String correlationId,
            Integer attachmentIndex) {
        return new RawObjectEvent(
                CURRENT_SCHEMA_VERSION, objectKey, sourceType, tenantId, ingestedAt,
                scopeDecision, contentHash, modality, participants, contentType, sizeBytes,
                null, sourceTimestamp, correlationId, attachmentIndex);
    }

    @Override
    public String dedupKey() {
        return contentHash;
    }
}
