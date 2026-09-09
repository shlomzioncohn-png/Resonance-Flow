package com.rnd.bridge.intake.model;

import java.time.Instant;

/**
 * The minimal per-message state persisted inside a {@link GroupState} so a group's transcript
 * can be rebuilt locally across runs without re-reading the source. Deliberately excludes
 * attachments — an attachment is only ever emitted from the batch that first saw it (see
 * ChatSessionGrouper/ChatGroupStateReconciler), so there is nothing to re-derive from a
 * snapshot of an older message.
 */
public record MessageSnapshot(String messageId, String sender, Instant timestamp, String text) {
}
