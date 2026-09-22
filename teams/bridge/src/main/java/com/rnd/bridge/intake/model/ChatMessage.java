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
 */
public record ChatMessage(
        RawSourceType sourceType,
        String tenantId,
        String chatId,
        String sender,
        Instant timestamp,
        String text,
        List<Attachment> attachments) {

    /** filename/mimeType/content of a chat message's attached file. */
    public record Attachment(String filename, String mimeType, byte[] content) {
    }
}
