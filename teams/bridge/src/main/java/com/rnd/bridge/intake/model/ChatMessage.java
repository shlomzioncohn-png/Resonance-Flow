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

    /** filename/mimeType/content of a chat message's attached file. */
    public record Attachment(String filename, String mimeType, byte[] content) {
    }
}
