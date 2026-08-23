package com.rnd.bridge.intake.unstructured.dto;

import com.rnd.bridge.shared.unstructured.dto.SourceType;
import java.time.Instant;

/**
 * One raw line from a chat export (Slack/WhatsApp), before session grouping.
 * chatId identifies the conversation/channel the message belongs to.
 * attachmentMimeType is set when the message is an attached file (e.g. WhatsApp's
 * "<attached: filename>" marker) rather than plain text — null otherwise.
 */
public record ChatMessage(
        SourceType sourceType,
        String tenantId,
        String chatId,
        String sender,
        Instant timestamp,
        String text,
        String attachmentMimeType) {
}
