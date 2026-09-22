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
 * attachmentFileId is Slack's own stable per-file id (see ChatMessage.Attachment.fileId) -
 * null for a body/transcript record, for any non-Slack attachment, and for a Slack attachment
 * from a source with no such id. It is Bridge-internal bookkeeping only (like sourceMessageIds
 * below), never mapped onto RawObjectEvent/the wire: UnstructuredIntakeLane.afterPublish reads
 * it (together with sourceMessageIds) to tell GroupStateStore.confirmAttachmentPublished exactly
 * which attachment a given Kafka publish was for, so a later retry/edit of the same message can
 * recognize this specific attachment was already published and skip republishing it - see
 * MessageSnapshot.confirmedAttachmentFileIds. Deliberately NOT attachmentIndex (below), whose
 * job is purely to keep this record's object key unique among sibling attachments - a position
 * can shift if an edit reorders or drops a file, but a fileId never does.
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
 * groupId is ChatGroupStateReconciler's own internal GroupState.groupId (a Slack threadId or a
 * minted time-gap session UUID - see GroupState's javadoc) that produced this record - Bridge-
 * internal bookkeeping only, like sourceMessageIds/attachmentFileId above, never mapped onto
 * RawObjectEvent/the wire. Null for every non-chat source and for the stateless chat path
 * (WhatsApp/fixtures, ChatSourceRecordMapper.toSourceRecords(List) - there is no persisted
 * GroupState to report an id for there). UnstructuredIntakeLane.afterPublish reads it directly to
 * know exactly which GroupState to confirm, instead of reverse-resolving one through
 * GroupStateStore's messageId index - that index exists purely for recognizing an edit/re-read of
 * a message already landed under some group (see GroupStateStore's class javadoc), not for
 * answering "which group produced this specific SourceRecord", and using it for the latter used to
 * silently break for a thread's root message, which GroupStateStore.writeMessageIndex deliberately
 * never indexes (see its javadoc) - see ChatGroupStateReconcilerTest/ChatEditWireContractTest for
 * the regression this was fixed for.
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
        String attachmentFileId,
        int attachmentIndex,
        int attachmentCount,
        List<String> sourceMessageIds,
        int revision,
        ChangeType changeType,
        String groupId) {

    /** Convenience constructor for every case except a Slack attachment record (which needs
     * attachmentFileId - see below) and Calendar's cancellation path - defaults attachmentFileId
     * to null, changeType to UPSERT, and groupId to null (no persisted GroupState behind this
     * record - see groupId's javadoc), the only values any other source/case ever needs. */
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
                rawContent, mimeType, attachmentFilename, null, attachmentIndex, attachmentCount, sourceMessageIds,
                revision, ChangeType.UPSERT, null);
    }

    /** Convenience constructor for Calendar's cancellation path - same attachmentFileId/groupId
     * defaults as above (Calendar attachments aren't implemented yet, and Calendar never has a
     * chat GroupState), explicit changeType. */
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
            int revision,
            ChangeType changeType) {
        this(sourceType, tenantId, naturalId, correlationId, occurredAt, sender, participants, subject, body,
                rawContent, mimeType, attachmentFilename, null, attachmentIndex, attachmentCount, sourceMessageIds,
                revision, changeType, null);
    }

    /** Convenience constructor for a chat transcript record built from a persisted GroupState -
     * same shape as the first convenience constructor (no attachmentFileId, always UPSERT) but
     * with a real groupId (see its javadoc) instead of defaulting it to null. */
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
            int revision,
            String groupId) {
        this(sourceType, tenantId, naturalId, correlationId, occurredAt, sender, participants, subject, body,
                rawContent, mimeType, attachmentFilename, null, attachmentIndex, attachmentCount, sourceMessageIds,
                revision, ChangeType.UPSERT, groupId);
    }

    /** Convenience constructor for a Slack attachment record built from a persisted GroupState -
     * the one case that needs both attachmentFileId (see its javadoc) and a real groupId (see
     * groupId's javadoc), but never a non-UPSERT changeType (Calendar is the only source with a
     * cancellation path, and it has no attachments yet). */
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
            String attachmentFileId,
            int attachmentIndex,
            int attachmentCount,
            List<String> sourceMessageIds,
            int revision,
            String groupId) {
        this(sourceType, tenantId, naturalId, correlationId, occurredAt, sender, participants, subject, body,
                rawContent, mimeType, attachmentFilename, attachmentFileId, attachmentIndex, attachmentCount,
                sourceMessageIds, revision, ChangeType.UPSERT, groupId);
    }
}
