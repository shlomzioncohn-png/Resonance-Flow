package com.rnd.bridge.intake.model;

import com.rnd.bridge.raw.model.ChangeType;
import com.rnd.bridge.raw.model.RawSourceType;
import java.time.Instant;
import java.util.List;

/**
 * The unit the scoping filter and landing pipeline operate on — one SourceRecord becomes
 * exactly one landed MinIO object and one RawObjectEvent.
 * For email/doc/calendar sources: one record per raw item.
 * For chat sources: one record per session (a run of ChatMessages grouped by
 * ChatSessionGrouper) — sender holds the joined participant list, subject is null,
 * body is the concatenated transcript used for scoping/keyword matching.
 * A single logical item (an email, a chat session) can produce MULTIPLE SourceRecords when
 * it has an attachment: one for the body/transcript text, one per attached file — each
 * gets its own object_key (a real attachment needs its own dedicated key so modality
 * routes an AI engine straight at it, not at a JSON wrapper around it). correlationId is
 * shared by every SourceRecord split off the same logical item, and ObjectKeyBuilder uses
 * it as the shared filename prefix — a convention, not a schema field (the official
 * RawObjectEvent schema has no way to express "these two events are related"). Every reader
 * populates it unconditionally, since it is also the stable identity Mind uses to recognize
 * that a later revision of the same logical item is an edit, not a new object — see
 * RawObjectEvent's javadoc for the wire-facing side of this.
 * participants is the real list backing the schema's participants field — every distinct
 * sender in a chat session, or the single sender/owner/organizer for other sources.
 * rawContent is the actual bytes to land in MinIO — UTF-8 text for body/transcript
 * records, real binary content for attachment records.
 * mimeType/attachmentFilename are both null for plain body/transcript records. When this
 * record represents an attachment (or a Drive file, which IS its own content), mimeType
 * drives Modality derivation and attachmentFilename becomes part of the object key.
 * attachmentIndex is 0 for body/transcript records, 1-based for attachment records (the
 * position of this attachment among its siblings sharing the same correlationId).
 * ObjectKeyBuilder includes it in the object key so two attachments on the same message
 * that happen to share a filename (e.g. two files both named "invoice.pdf") still get
 * distinct keys instead of silently overwriting each other in MinIO.
 * attachmentCount is the total number of attachment records sharing this record's
 * correlationId (never counting the body/transcript itself) — 0 for a logical item with no
 * attachments. Every record split off the same logical item (the body and every attachment)
 * carries the same value, verbatim onto RawObjectEvent.attachmentCount() by
 * UnstructuredPayloadMapper — see RawObjectEvent's javadoc for the wire-facing side of this.
 * sourceMessageIds is the stable per-message id(s) (ChatMessage.id — Slack's ts) of the raw
 * chat messages this record was built from: every message in the session for a transcript
 * record, or the single owning message for an attachment record. Empty (never null) for
 * non-chat sources and for chat messages with no stable id (WhatsApp, fixtures) — there is
 * nothing to preserve there yet. Not yet consumed downstream; this is groundwork for future
 * edit/delete handling (see Bridge issue #264), not a schema or contract change.
 * revision is the persisted GroupState's revision this record's content represents (see
 * ChatGroupStateReconciler) — 0 for every non-chat source. UnstructuredIntakeLane.afterPublish
 * reads it off this record (not off RawObjectEvent) to tell GroupStateStore.confirmPublished
 * exactly which revision a given Kafka ack belongs to; without it, a stale ack for a
 * since-superseded revision could incorrectly mark the group's newer, not-yet-actually-published
 * content as confirmed. Also mapped verbatim onto RawObjectEvent.revision() by
 * UnstructuredPayloadMapper — see RawObjectEvent's javadoc for the wire-facing side of this.
 * changeType is mapped verbatim onto RawObjectEvent.changeType() by UnstructuredPayloadMapper —
 * UPSERT for every source/case except one: a cancelled Google Calendar event (event.status ==
 * "cancelled"), which RealCalendarSourceReader.toCancelledSourceRecord builds with
 * DELETE_REVIEW instead, at the same correlationId/incremented revision
 * CalendarEventStateReconciler would have assigned an ordinary edit (the reconciler itself has
 * no notion of "cancellation" — it only tracks etag changes; deciding changeType is entirely
 * this field's job). The convenience constructor below defaults to UPSERT precisely so every
 * other reader/mapper never has to think about this field at all.
 */
public record SourceRecord(
        RawSourceType sourceType,
        String tenantId,
        String naturalId,
        String correlationId,
        Instant occurredAt,
        String sender,
        List<String> participants,
        String subject,
        String body,
        byte[] rawContent,
        String mimeType,
        String attachmentFilename,
        int attachmentIndex,
        int attachmentCount,
        List<String> sourceMessageIds,
        int revision,
        ChangeType changeType) {

    /** Convenience constructor for every case except Calendar's cancellation path - defaults
     * changeType to UPSERT, the only value any other source/case ever needs. */
    public SourceRecord(
            RawSourceType sourceType,
            String tenantId,
            String naturalId,
            String correlationId,
            Instant occurredAt,
            String sender,
            List<String> participants,
            String subject,
            String body,
            byte[] rawContent,
            String mimeType,
            String attachmentFilename,
            int attachmentIndex,
            int attachmentCount,
            List<String> sourceMessageIds,
            int revision) {
        this(sourceType, tenantId, naturalId, correlationId, occurredAt, sender, participants, subject, body,
                rawContent, mimeType, attachmentFilename, attachmentIndex, attachmentCount, sourceMessageIds,
                revision, ChangeType.UPSERT);
    }
}
