package com.rnd.bridge.raw.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.rnd.bridge.core.BridgePayload;
import java.time.Instant;
import java.util.List;

/**
 * Published to Kafka topic bridge.raw.landed by both unstructured lanes (Malki and
 * Esther). schemaVersion is still "1.0" in practice - the record shape below changed to the multi-object contract,
 * but the version string wasn't bumped since nothing is in production yet to distinguish an old "1.0" consumer from this new shape:
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
 * changeType is UPSERT for a new object or an edit; DELETE_REVIEW is already emitted by the
 * Calendar reader on event cancellation (see RealCalendarSourceReader/
 * FixtureCalendarSourceReader) and reserved for the same whole-item-deleted case on every
 * other source once that's built (see below) - not yet emitted by Slack/Email/Drive/WhatsApp.
 *
 * changeSignificanceScore (0-100) has no real calculation yet - every producer hardcodes
 * CURRENT_CHANGE_SIGNIFICANCE_SCORE until that logic exists.
 *
 * objects must never be empty for changeType=UPSERT - a RawObjectEvent is only ever
 * published once every MinIO object it refers to has actually landed successfully; a
 * partial object list is never published (see UnstructuredIntakeLane/
 * MediaIntakeOrchestrator, the two producers). changeType=DELETE_REVIEW is the one
 * exception: an empty objects list there means "this logical item no longer exists."
 *
 * implements BridgePayload (see core.BridgePayload): tenantId() is satisfied by the
 * record's own tenantId component; dedupKey() is correlationId + ":" + revision - unlike
 * the old per-object contentHash, no single object's hash can stand in for "this whole
 * published revision of this logical item" once there can be more than one object.
 * This key is only valid as a dedup identity under two invariants, agreed with Mind
 * (Yonit Shvarts): correlationId is stable across every revision of the same logical
 * item, regardless of source type, and revision is strictly increasing per correlationId
 * - never reused, never decreasing.
 *
 * A whole logical item being deleted (e.g. an entire Slack session/thread, not just one
 * message edited out of it - that case is a plain revision bump with changeType=UPSERT)
 * is published the same way a calendar cancellation already is (see
 * RealCalendarSourceReader/FixtureCalendarSourceReader): a new revision of the same
 * correlationId with changeType=DELETE_REVIEW and an empty objects list.
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
