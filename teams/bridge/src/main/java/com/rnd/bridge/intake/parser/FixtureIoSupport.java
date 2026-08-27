package com.rnd.bridge.intake.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.bridge.intake.model.ChatMessage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.springframework.core.io.ClassPathResource;

/**
 * Fixture file-reading helpers shared by the per-source Fixture...Reader classes
 * (intake/parser/{email,drive,calendar,slack,whatsapp}). Kept separate from those classes
 * because it's the one piece of I/O plumbing all of them need, regardless of the JSON shape
 * each source parses.
 */
public final class FixtureIoSupport {

    public static final String FIXTURE_ROOT = "fixtures/unstructured/";

    private FixtureIoSupport() {
    }

    public static JsonNode readJson(ObjectMapper objectMapper, String classpathLocation) {
        try (InputStream in = new ClassPathResource(classpathLocation).getInputStream()) {
            return objectMapper.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load fixture: " + classpathLocation, e);
        }
    }

    public static String textOrNull(JsonNode node) {
        return (node == null || node.isNull()) ? null : node.asText();
    }

    public static ChatMessage.Attachment readAttachmentFile(String filename, String mimeType) {
        try (InputStream in = new ClassPathResource(FIXTURE_ROOT + filename).getInputStream()) {
            byte[] content = in.readAllBytes();
            return new ChatMessage.Attachment(filename, mimeType, content);
        } catch (IOException e) {
            throw new UncheckedIOException("Referenced chat attachment not found in fixtures: " + filename, e);
        }
    }
}
