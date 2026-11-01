package com.rnd.bridge.intake.model;

import java.time.Instant;
import java.util.List;

/**
 * The minimal per-message state persisted inside a {@link GroupState} so a group's transcript
 * can be rebuilt locally across runs without re-reading the source. Deliberately excludes
 * attachment content — an attachment's bytes are only ever emitted from the batch that first
 * saw it (see ChatSessionGrouper/ChatGroupStateReconciler), so there is nothing to re-derive
 * from a snapshot of an older message.
 * attachmentCount is the one piece of attachment-related state that IS persisted here — how
 * many attachments this specific message carried when it was last merged in. Needed because a
 * group's total attachment count (see RawObjectEvent.attachmentCount) must stay correct even
 * when a message with attachments was merged in a previous intake run and never reappears in a
 * later batch — without this, a later run building SourceRecords from only its own batch's
 * messages would have no way to know about attachments a prior run already accounted for.
 * landedAttachments is this message's Slack attachments (see ChatMessage.Attachment) that have
 * already been landed to MinIO during the session's real-time arrival - populated by
 * GroupStateStore.recordLandedAttachment, called directly from SlackEventsController once a
 * specific attachment's MinIO put actually succeeds (not a Kafka publish - publication for a
 * non-thread session happens exactly once, for the whole group, at session close; see
 * SlackSessionPublisher). GroupStateMutator.mergeSnapshots carries this forward (filtered
 * to whatever attachments the message still has) on every re-merge of the same messageId, so a
 * Slack retry or an unrelated text edit of an already-landed attachment never re-lands it - only
 * an attachment whose file id isn't in this set yet is still eligible. Deliberately keyed on
 * Slack's own stable file id, not attachmentIndex (a position, which can shift if an edit
 * reorders or removes a file) - see SourceRecord.attachmentFileId's javadoc.
 */
public record MessageSnapshot(
        String messageId, String sender, Instant timestamp, String text, int attachmentCount,
        List<LandedAttachment> landedAttachments) {

    public MessageSnapshot {
        landedAttachments = landedAttachments == null ? List.of() : List.copyOf(landedAttachments);
    }

    /** Convenience constructor for a message with no landed attachments yet - every message
     * starts here, including ones with no attachments at all. */
    public MessageSnapshot(String messageId, String sender, Instant timestamp, String text, int attachmentCount) {
        this(messageId, sender, timestamp, text, attachmentCount, List.of());
    }
}
