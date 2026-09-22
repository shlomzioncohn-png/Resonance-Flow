package com.rnd.bridge.intake.model;

import java.time.Instant;

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
 */
public record MessageSnapshot(String messageId, String sender, Instant timestamp, String text, int attachmentCount) {

    /** Convenience constructor for a message with no attachments - defaults attachmentCount to 0. */
    public MessageSnapshot(String messageId, String sender, Instant timestamp, String text) {
        this(messageId, sender, timestamp, text, 0);
    }
}
