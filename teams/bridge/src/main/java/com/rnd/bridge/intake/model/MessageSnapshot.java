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
 * confirmedAttachmentFileIds is the set of this message's Slack attachment file ids (see
 * ChatMessage.Attachment) that have actually been published to Kafka - populated by
 * GroupStateStore.confirmAttachmentPublished, called from UnstructuredIntakeLane.afterPublish
 * once a specific attachment's publish actually succeeds. ChatGroupStateReconciler.mergeSnapshots
 * carries this forward (filtered to whatever attachments the message still has) on every
 * re-merge of the same messageId, so a Slack retry or an unrelated text edit of an already-
 * confirmed attachment never re-publishes it - only an attachment whose file id isn't in this
 * set yet is still eligible. Deliberately keyed on Slack's own stable file id, not
 * attachmentIndex (a position, which can shift if an edit reorders or removes a file) - see
 * SourceRecord.attachmentFileId's javadoc.
 */
public record MessageSnapshot(
        String messageId, String sender, Instant timestamp, String text, int attachmentCount,
        List<String> confirmedAttachmentFileIds) {

    public MessageSnapshot {
        confirmedAttachmentFileIds = confirmedAttachmentFileIds == null ? List.of() : List.copyOf(confirmedAttachmentFileIds);
    }

    /** Convenience constructor for a message with no confirmed attachment publications yet -
     * every message starts here, including ones with no attachments at all. */
    public MessageSnapshot(String messageId, String sender, Instant timestamp, String text, int attachmentCount) {
        this(messageId, sender, timestamp, text, attachmentCount, List.of());
    }
}
