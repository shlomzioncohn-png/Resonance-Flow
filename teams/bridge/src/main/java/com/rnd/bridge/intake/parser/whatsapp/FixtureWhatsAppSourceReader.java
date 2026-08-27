package com.rnd.bridge.intake.parser.whatsapp;

import static com.rnd.bridge.intake.parser.FixtureIoSupport.FIXTURE_ROOT;
import static com.rnd.bridge.intake.parser.FixtureIoSupport.readAttachmentFile;

import com.rnd.bridge.intake.model.ChatMessage;
import com.rnd.bridge.raw.model.RawSourceType;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Parses whatsapp-*-sample.txt files. Moved as-is from the old
 * FixtureSourceLoader.loadWhatsAppMessages() — see plan.md for why this reads the real
 * WhatsApp export line format, one .txt file per chat.
 */
@Component
@ConditionalOnProperty(
        prefix = "bridge.intake.whatsapp", name = "mode", havingValue = "fixture", matchIfMissing = true)
public class FixtureWhatsAppSourceReader implements WhatsAppSourceReader {

    private static final DateTimeFormatter WHATSAPP_TIMESTAMP = DateTimeFormatter.ofPattern("dd/MM/yyyy, HH:mm");
    private static final Pattern WHATSAPP_LINE =
            Pattern.compile("^(\\d{2}/\\d{2}/\\d{4}, \\d{2}:\\d{2}) - ([^:]+): (.*)$");
    private static final Pattern WHATSAPP_ATTACHMENT = Pattern.compile("^<attached: (.+)>$");
    private static final String WHATSAPP_TENANT_ID = "kamatech";

    // Extension -> MIME type for WhatsApp's "<attached: filename>" marker; the actual bytes
    // are read from a same-named fixture file, matching how WhatsApp really exports media
    // (alongside the chat .txt, not embedded inside it).
    private static final Map<String, String> ATTACHMENT_MIME_BY_EXTENSION = Map.of(
            "opus", "audio/opus",
            "m4a", "audio/mp4",
            "mp3", "audio/mpeg",
            "wav", "audio/wav",
            "pdf", "application/pdf",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "png", "image/png");

    private final ResourcePatternResolver resourcePatternResolver;

    public FixtureWhatsAppSourceReader(ResourcePatternResolver resourcePatternResolver) {
        this.resourcePatternResolver = resourcePatternResolver;
    }

    @Override
    public List<ChatMessage> read() {
        List<ChatMessage> messages = new ArrayList<>();
        try {
            Resource[] resources =
                    resourcePatternResolver.getResources("classpath:" + FIXTURE_ROOT + "whatsapp-*-sample.txt");
            for (Resource resource : resources) {
                String chatId = stripExtension(resource.getFilename());
                messages.addAll(parseFile(resource, chatId));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load WhatsApp fixtures", e);
        }
        return messages;
    }

    private List<ChatMessage> parseFile(Resource resource, String chatId) throws IOException {
        List<ChatMessage> messages = new ArrayList<>();
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                Matcher matcher = WHATSAPP_LINE.matcher(line);
                if (!matcher.matches()) {
                    continue;
                }
                LocalDateTime localTimestamp = LocalDateTime.parse(matcher.group(1), WHATSAPP_TIMESTAMP);
                String text = matcher.group(3);
                messages.add(new ChatMessage(
                        RawSourceType.WHATSAPP,
                        WHATSAPP_TENANT_ID,
                        chatId,
                        matcher.group(2),
                        localTimestamp.toInstant(ZoneOffset.UTC),
                        text,
                        resolveAttachments(text)));
            }
        }
        return messages;
    }

    // WhatsApp's "<attached: filename>" marker is one attachment per line, at most - unlike
    // Slack's real "files" array, which can carry several. Still returns a List so callers
    // (ChatSessionGrouper) handle every chat source uniformly.
    private List<ChatMessage.Attachment> resolveAttachments(String text) {
        Matcher matcher = WHATSAPP_ATTACHMENT.matcher(text);
        if (!matcher.matches()) {
            return List.of();
        }
        String filename = matcher.group(1);
        int dot = filename.lastIndexOf('.');
        String mimeType = dot < 0 ? null : ATTACHMENT_MIME_BY_EXTENSION.get(filename.substring(dot + 1).toLowerCase());
        return List.of(readAttachmentFile(filename, mimeType));
    }

    private static String stripExtension(String filename) {
        if (filename == null) {
            return "unknown-chat";
        }
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
