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
 * correlationId/attachmentIndex are a Bridge-proposed addition, confirmed by Mind. Every
 * producer (email/calendar/drive/chat readers) always populates correlationId — it is the
 * stable identity of the logical source item, not just a linking convention for split
 * attachments: for chat it is minted once per group and carried forward unchanged across
 * every subsequent edit (see ChatGroupStateReconciler), so Mind can use it to recognize
 * that a later revision of the same correlationId is an edit of an already-seen object,
 * not a new one. It is typed as a nullable String only because Java records have no
 * non-null primitive for String; in practice it is required and never sent as null.
 *
 * revision/changeType are a further Bridge-proposed addition, beyond the official schema's
 * additionalProperties: false, needed to carry chat edit/reconciliation state (see
 * ChatGroupStateReconciler) to Mind — flag any incompatibility with Mind's actual consumer
 * validation the same way correlationId/attachmentIndex needed sign-off. revision is
 * SourceRecord.revision() verbatim: the same correlationId republished at a higher revision
 * is a content edit, not a new logical object — 0 for every non-chat source, which never
 * republishes an existing correlationId. changeType is always UPSERT today (a new object or
 * an edit are the same instruction to Mind: store/replace this revision under this
 * correlationId); DELETE_REVIEW is defined on the enum but never emitted yet — see
 * ChangeType's javadoc. Neither field is derived from groupId/threadId/messageId — those stay
 * Bridge-internal (see GroupState's javadoc) and never reach this record.
 *
 * attachmentCount is a Mind-confirmed-required addition: the total number of attachment
 * files belonging to this logical object (never counting the body/transcript itself; 0 when
 * there are none). SourceRecord.attachmentCount() verbatim — see its javadoc for how every
 * record split off the same logical item (body + every attachment) is guaranteed to carry the
 * same value, including for a Slack group whose messages/attachments span more than one
 * intake run (see MessageSnapshot.attachmentCount and ChatSourceRecordMapper).
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
        Integer attachmentIndex,
        int attachmentCount,
        int revision,
        ChangeType changeType) implements BridgePayload {

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
            Integer attachmentIndex,
            int attachmentCount,
            int revision,
            ChangeType changeType) {
        return new RawObjectEvent(
                CURRENT_SCHEMA_VERSION, objectKey, sourceType, tenantId, ingestedAt,
                scopeDecision, contentHash, modality, participants, contentType, sizeBytes,
                null, sourceTimestamp, correlationId, attachmentIndex, attachmentCount, revision, changeType);
    }

    @Override
    public String dedupKey() {
        return contentHash;
    }
}
