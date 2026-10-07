package com.rnd.bridge.raw.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.rnd.bridge.core.BridgePayload;
import java.time.Instant;
import java.util.List;

/**
 * Published to Kafka topic bridge.raw.landed by both unstructured lanes (Malki and
 * Esther). schemaVersion "2.0" - a breaking change from the "1.0" flat-object contract:
 * one RawObjectEvent now represents one logical item (an email, a chat session, a
 * meeting recording), carrying every MinIO object that belongs to it - body/transcript
 * and every attachment alike - as a list of {@link RawObjectRef} entries, instead of one
 * logical item fanning out into several separate Kafka events keyed by attachment_index.
 * object_key/content_hash/content_type/size_bytes/modality moved onto each RawObjectRef
 * (they are per-object, not per-logical-item); attachment_index/attachment_count are
 * gone (replaced by RawObjectRef.role - see Role's javadoc); transcript_object_key is
 * gone (a transcript is now just a RawObjectRef with role=TRANSCRIPT in the list).
 *
 * correlationId is the stable identity of the logical item - minted once and carried
 * forward unchanged across every subsequent edit (see ChatGroupStateReconciler for the
 * chat case), so Mind can recognize that a later revision of the same correlationId is
 * an edit of an already-seen object, not a new one. Required (never null in practice).
 *
 * revision/changeType carry chat edit/reconciliation state (see ChatGroupStateReconciler)
 * to Mind: revision is 0 for every non-chat source, which never republishes an existing
 * correlationId; a chat edit republishes the same correlationId at a higher revision.
 * changeType is UPSERT for a new object or an edit; DELETE_REVIEW is defined but not
 * emitted by any producer yet.
 *
 * changeSignificanceScore (0-100) has no real calculation yet - every producer hardcodes
 * CURRENT_CHANGE_SIGNIFICANCE_SCORE until that logic exists.
 *
 * objects must never be empty - a RawObjectEvent is only ever published once every
 * MinIO object it refers to has actually landed successfully; a partial object list is
 * never published (see UnstructuredIntakeLane/MediaIntakeOrchestrator, the two producers).
 *
 * implements BridgePayload (see core.BridgePayload): tenantId() is satisfied by the
 * record's own tenantId component; dedupKey() is correlationId + ":" + revision - unlike
 * the old per-object contentHash, no single object's hash can stand in for "this whole
 * published revision of this logical item" once there can be more than one object.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RawObjectEvent(
        String schemaVersion,
        RawSourceType sourceType,
        String tenantId,
        Instant ingestedAt,
        ScopeDecision scopeDecision,
        List<String> participants,
        Instant sourceTimestamp,
        String correlationId,
        int revision,
        ChangeType changeType,
        int changeSignificanceScore,
        List<RawObjectRef> objects) implements BridgePayload {

    private static final String CURRENT_SCHEMA_VERSION = "1.0";
    private static final int CURRENT_CHANGE_SIGNIFICANCE_SCORE = 100;

    public static RawObjectEvent create(
            RawSourceType sourceType,
            String tenantId,
            Instant ingestedAt,
            ScopeDecision scopeDecision,
            List<String> participants,
            Instant sourceTimestamp,
            String correlationId,
            int revision,
            ChangeType changeType,
            List<RawObjectRef> objects) {
        return new RawObjectEvent(
                CURRENT_SCHEMA_VERSION, sourceType, tenantId, ingestedAt, scopeDecision, participants,
                sourceTimestamp, correlationId, revision, changeType, CURRENT_CHANGE_SIGNIFICANCE_SCORE, objects);
    }

    @Override
    public String dedupKey() {
        return correlationId + ":" + revision;
    }
}
