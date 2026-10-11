package com.rnd.bridge.raw.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.rnd.bridge.core.BridgePayload;
import java.time.Instant;
import java.util.List;

/**
 * Published to Kafka topic bridge.raw.landed by both unstructured lanes (Malki and
 * Esther). One event = one logical source item (an email, a meeting), never one event per
 * physical file - every related MinIO object it owns (a message body plus its attachments,
 * or a recording plus its transcript/platform-metadata sidecar) is one entry in
 * {@link #objects()} instead of its own separately-published event. Field names are
 * camelCase in Java but serialize as snake_case on the wire via @JsonNaming.
 * <p>
 * This replaces the earlier shape where object_key/content_hash/content_type/size_bytes/
 * modality sat directly on the event (describing only its one primary file) plus bespoke
 * pointer fields for anything else (transcript_object_key, the former
 * additional_metadata_object_key) and attachment_index/attachment_count for splitting one
 * logical item across several events. Approved on Mind's side 2026-10 (issue #31) - see that
 * thread for the full before/after discussion.
 * <p>
 * correlationId is now required, not optional: with content_hash moved into each
 * {@link RawObjectRef}, there's no single event-level hash left to key/dedup on, and a
 * content hash was never a good fit for revisions anyway (an edited body gets a different
 * hash, scattering revisions of the same logical item). correlationId stays fixed across
 * revisions by construction, so {@link #dedupKey()} is correlationId, not a hash - see
 * {@code media.MediaIntakeOrchestrator}/{@code intake.service.PointerEventPublisher} for how
 * each lane generates it (fresh per logical item, reused across its revisions).
 * <p>
 * sourceRef is a pointer back to the original item in the *source system* itself (a Zoom
 * share_url, a Slack permalink, an email Message-ID) - distinct from every object_key in
 * {@link #objects()}, which only ever points into Bridge's own MinIO copy. Lets a UI jump to
 * "the real thing" without reading a MinIO object just to find that one string.
 * <p>
 * revision/changeType/changeSignificanceScore support content that gets edited after first
 * landing (Malki's lane) - both lanes send revision 0 / UPSERT / 100 for a brand-new item;
 * neither Zoom/BBB recordings nor scanned docs are ever revised once landed, so Esther's
 * lane always sends exactly that, never anything else.
 * <p>
 * implements BridgePayload (see core.BridgePayload): tenantId() is satisfied by the record's
 * own tenantId component.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RawObjectEvent(
        String schemaVersion,
        RawSourceType sourceType,
        String tenantId,
        String correlationId,
        Instant ingestedAt,
        ScopeDecision scopeDecision,
        int revision,
        ChangeType changeType,
        Integer changeSignificanceScore,
        List<String> participants,
        Instant sourceTimestamp,
        String sourceRef,
        List<RawObjectRef> objects) implements BridgePayload {

    private static final String CURRENT_SCHEMA_VERSION = "1.0";

    public static RawObjectEvent create(
            RawSourceType sourceType,
            String tenantId,
            String correlationId,
            Instant ingestedAt,
            ScopeDecision scopeDecision,
            int revision,
            ChangeType changeType,
            Integer changeSignificanceScore,
            List<String> participants,
            Instant sourceTimestamp,
            String sourceRef,
            List<RawObjectRef> objects) {
        return new RawObjectEvent(
                CURRENT_SCHEMA_VERSION, sourceType, tenantId, correlationId, ingestedAt,
                scopeDecision, revision, changeType, changeSignificanceScore, participants,
                sourceTimestamp, sourceRef, objects);
    }

    @Override
    public String dedupKey() {
        return correlationId;
    }
}
