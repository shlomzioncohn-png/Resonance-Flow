package com.rnd.bridge.intake.parser.whatsapp;

import com.rnd.bridge.intake.config.BridgeTenantId;
import com.rnd.bridge.intake.config.WhatsAppImportProperties;
import com.rnd.bridge.intake.model.ChatMessage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Reads real WhatsApp export .txt files from a configured import directory instead of
 * classpath fixtures. There is no official export API for personal WhatsApp (see plan.md) —
 * someone has to use WhatsApp's own "Export Chat" feature and place the resulting .txt (and
 * any attachment files, alongside it) into bridge.intake.whatsapp.import-dir; this reader
 * just picks up whatever .txt files are there. Uses the same line format and attachment
 * conventions as the fixtures, via the shared WhatsAppExportLineParser.
 */
@Component
@ConditionalOnProperty(prefix = "bridge.intake.whatsapp", name = "mode", havingValue = "real")
public class RealWhatsAppSourceReader implements WhatsAppSourceReader {

    private final WhatsAppImportProperties properties;
    private final BridgeTenantId tenantId;

    public RealWhatsAppSourceReader(WhatsAppImportProperties properties, BridgeTenantId tenantId) {
        this.properties = properties;
        this.tenantId = tenantId;
    }

    @Override
    public List<ChatMessage> read() {
        Path importDir = resolveImportDir();
        List<ChatMessage> messages = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(importDir, "*.txt")) {
            for (Path file : files) {
                String chatId = stripExtension(file.getFileName().toString());
                messages.addAll(parseFile(importDir, file, chatId));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read WhatsApp import directory: " + importDir, e);
        }
        return messages;
    }

    private Path resolveImportDir() {
        String importDir = properties.importDir();
        if (importDir == null || importDir.isBlank()) {
            throw new IllegalStateException(
                    "bridge.intake.whatsapp.import-dir must be set when bridge.intake.whatsapp.mode=real");
        }
        Path path = Path.of(importDir);
        if (!Files.isDirectory(path)) {
            throw new IllegalStateException("bridge.intake.whatsapp.import-dir is not a directory: " + path);
        }
        return path;
    }

    private List<ChatMessage> parseFile(Path importDir, Path file, String chatId) throws IOException {
        List<ChatMessage> messages = new ArrayList<>();
        ZoneId exportZone = ZoneId.of(properties.zoneId());
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                WhatsAppExportLineParser.parseLine(
                                tenantId.value(), chatId, line, exportZone, (filename, mimeType) ->
                                        readAttachment(importDir, filename, mimeType))
                        .ifPresent(messages::add);
            }
        }
        return messages;
    }

    private ChatMessage.Attachment readAttachment(Path importDir, String filename, String mimeType) {
        Path attachmentPath = importDir.resolve(filename);
        try {
            byte[] content = Files.readAllBytes(attachmentPath);
            return new ChatMessage.Attachment(filename, mimeType, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Referenced chat attachment not found: " + attachmentPath, e);
        }
    }

    private static String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
