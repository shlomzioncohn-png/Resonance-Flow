package com.rnd.bridge.intake.model;

import com.rnd.bridge.raw.model.RawSourceType;
import java.time.Instant;
import java.util.List;

/**
 * One raw line from a chat export (Slack/WhatsApp), before session grouping.
 * chatId identifies the conversation/channel the message belongs to.
 * attachments is the files attached to this message — empty (never null) for plain text.
 * A single message can carry more than one (Slack's real "files" array can); WhatsApp's
 * "<attached: filename>" marker only ever produces zero or one.
 * id is a platform-stable per-message identifier (Slack's ts) — null where no such id exists
 * (WhatsApp export, fixtures). threadId is null unless this message belongs to a Slack
 * thread, in which case it's the thread root's id (shared by the root message itself and
 * every reply) — see ChatSessionGrouper, which groups by threadId instead of time-gap when
 * present.
 */
public record ChatMessage(
        RawSourceType sourceType,
        String tenantId,
        String chatId,
        String sender,
        Instant timestamp,
        String text,
        List<Attachment> attachments,
        String id,
        String threadId) {

    /** Convenience constructor for sources with no stable per-message/thread id. */
    public ChatMessage(
            RawSourceType sourceType,
            String tenantId,
            String chatId,
            String sender,
            Instant timestamp,
            String text,
            List<Attachment> attachments) {
        this(sourceType, tenantId, chatId, sender, timestamp, text, attachments, null, null);
    }

    /**
     * filename/mimeType/content of a chat message's attached file. fileId is Slack's own stable
     * file id (File.getId() - e.g. "F0123ABC"), present for every real Slack attachment; null
     * for sources with no such stable id (WhatsApp export, fixtures). Unlike attachmentIndex
     * (position within a message's file list, used only for ordering/object-key uniqueness -
     * see SourceRecord), fileId never changes across a retry or an unrelated edit of the same
     * message, so it - paired with the owning message's id - is what GroupStateStore uses to
     * recognize "this exact attachment was already published" (see
     * ChatGroupStateReconciler.mergeSnapshots and UnstructuredIntakeLane.afterPublish).
     */
    public record Attachment(String fileId, String filename, String mimeType, byte[] content) {

        /** Convenience constructor for sources with no stable per-attachment id (WhatsApp export,
         * fixtures) - see fileId's javadoc above. */
        public Attachment(String filename, String mimeType, byte[] content) {
            this(null, filename, mimeType, content);
        }
    }
}
