package com.rnd.bridge.intake.parser.whatsapp;

import com.rnd.bridge.intake.model.ChatMessage;
import com.rnd.bridge.raw.model.RawSourceType;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses one line of the real WhatsApp export format ("dd/MM/yyyy, HH:mm - sender: text").
 * Shared by FixtureWhatsAppSourceReader (classpath fixtures) and RealWhatsAppSourceReader
 * (files dropped into a real import directory) — the export line format itself doesn't
 * change between the two, only where the .txt file and its attachments come from, which is
 * why attachment bytes are resolved through a caller-supplied AttachmentResolver instead of
 * this class reading files itself.
 */
final class WhatsAppExportLineParser {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("dd/MM/yyyy, HH:mm");
    private static final Pattern LINE = Pattern.compile("^(\\d{2}/\\d{2}/\\d{4}, \\d{2}:\\d{2}) - ([^:]+): (.*)$");
    private static final Pattern ATTACHMENT = Pattern.compile("^<attached: (.+)>$");

    // Extension -> MIME type for WhatsApp's "<attached: filename>" marker; the actual bytes
    // are read from a same-named file alongside the chat .txt, matching how WhatsApp really
    // exports media (not embedded inside the .txt).
    private static final Map<String, String> ATTACHMENT_MIME_BY_EXTENSION = Map.of(
            "opus", "audio/opus",
            "m4a", "audio/mp4",
            "mp3", "audio/mpeg",
            "wav", "audio/wav",
            "pdf", "application/pdf",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "png", "image/png");

    private WhatsAppExportLineParser() {
    }

    @FunctionalInterface
    interface AttachmentResolver {
        ChatMessage.Attachment resolve(String filename, String mimeType);
    }

    static Optional<ChatMessage> parseLine(
            String tenantId, String chatId, String line, AttachmentResolver attachmentResolver) {
        Matcher matcher = LINE.matcher(line);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        LocalDateTime localTimestamp = LocalDateTime.parse(matcher.group(1), TIMESTAMP);
        String text = matcher.group(3);
        return Optional.of(new ChatMessage(
                RawSourceType.WHATSAPP,
                tenantId,
                chatId,
                matcher.group(2),
                localTimestamp.toInstant(ZoneOffset.UTC),
                text,
                resolveAttachments(text, attachmentResolver)));
    }

    // WhatsApp's "<attached: filename>" marker is one attachment per line, at most - unlike
    // Slack's real "files" array, which can carry several. Still returns a List so callers
    // (ChatSessionGrouper) handle every chat source uniformly.
    private static List<ChatMessage.Attachment> resolveAttachments(String text, AttachmentResolver attachmentResolver) {
        Matcher matcher = ATTACHMENT.matcher(text);
        if (!matcher.matches()) {
            return List.of();
        }
        String filename = matcher.group(1);
        int dot = filename.lastIndexOf('.');
        String mimeType = dot < 0 ? null : ATTACHMENT_MIME_BY_EXTENSION.get(filename.substring(dot + 1).toLowerCase());
        return List.of(attachmentResolver.resolve(filename, mimeType));
    }
}
