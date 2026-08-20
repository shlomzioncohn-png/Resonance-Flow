package com.rnd.bridge.intake.unstructured.dto;

import java.time.Instant;

/**
 * One raw line from a chat export (Slack/WhatsApp), before session grouping.
 * chatId identifies the conversation/channel the message belongs to.
 */
public record ChatMessage(
        SourceType sourceType,
        String tenantId,
        String chatId,
        String sender,
        Instant timestamp,
        String text) {
}
