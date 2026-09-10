package com.rnd.bridge.intake.parser.whatsapp;

import static com.rnd.bridge.intake.parser.FixtureIoSupport.FIXTURE_ROOT;
import static com.rnd.bridge.intake.parser.FixtureIoSupport.readAttachmentFile;

import com.rnd.bridge.intake.model.ChatMessage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Parses whatsapp-*-sample.txt fixture files using the shared WhatsAppExportLineParser — see
 * plan.md for why fixtures use the real WhatsApp export line format, one .txt file per chat.
 */
@Component
@ConditionalOnProperty(
        prefix = "bridge.intake.whatsapp", name = "mode", havingValue = "fixture", matchIfMissing = true)
public class FixtureWhatsAppSourceReader implements WhatsAppSourceReader {

    private static final String WHATSAPP_TENANT_ID = "kamatech";
    // Fixtures are written as if exported from a phone in Israel, matching WHATSAPP_TENANT_ID.
    private static final ZoneId WHATSAPP_EXPORT_ZONE = ZoneId.of("Asia/Jerusalem");

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
                WhatsAppExportLineParser.parseLine(
                                WHATSAPP_TENANT_ID, chatId, line, WHATSAPP_EXPORT_ZONE,
                                FixtureWhatsAppSourceReader::readAttachment)
                        .ifPresent(messages::add);
            }
        }
        return messages;
    }

    private static ChatMessage.Attachment readAttachment(String filename, String mimeType) {
        return readAttachmentFile(filename, mimeType);
    }

    private static String stripExtension(String filename) {
        if (filename == null) {
            return "unknown-chat";
        }
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
