package com.rnd.bridge.intake.unstructured.fixture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.bridge.intake.unstructured.dto.ChatMessage;
import com.rnd.bridge.intake.unstructured.dto.SourceRecord;
import com.rnd.bridge.intake.unstructured.dto.SourceType;
import com.rnd.bridge.intake.unstructured.service.ChatSessionGrouper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

/**
 * Reads and parses each source's fixture file(s) into SourceRecords. Field mapping is
 * source-specific (see plan.md) because Gmail/Drive/Calendar/Slack use different real-world
 * field names. Chat sources (Slack, WhatsApp) are parsed into ChatMessages first, then
 * grouped into sessions by ChatSessionGrouper so callers always get List<SourceRecord>
 * regardless of the underlying source shape.
 */
@Component
public class FixtureSourceLoader {

    private static final String FIXTURE_ROOT = "fixtures/unstructured/";
    private static final DateTimeFormatter WHATSAPP_TIMESTAMP = DateTimeFormatter.ofPattern("dd/MM/yyyy, HH:mm");
    private static final Pattern WHATSAPP_LINE =
            Pattern.compile("^(\\d{2}/\\d{2}/\\d{4}, \\d{2}:\\d{2}) - ([^:]+): (.*)$");
    private static final String WHATSAPP_TENANT_ID = "kamatech";

    private final ObjectMapper objectMapper;
    private final ChatSessionGrouper chatSessionGrouper;
    private final ResourcePatternResolver resourcePatternResolver;

    public FixtureSourceLoader(
            ObjectMapper objectMapper,
            ChatSessionGrouper chatSessionGrouper,
            ResourcePatternResolver resourcePatternResolver) {
        this.objectMapper = objectMapper;
        this.chatSessionGrouper = chatSessionGrouper;
        this.resourcePatternResolver = resourcePatternResolver;
    }

    public List<SourceRecord> load(SourceType sourceType) {
        return switch (sourceType) {
            case EMAIL -> loadEmail();
            case DRIVE_DOC -> loadDrive();
            case CALENDAR -> loadCalendar();
            case SLACK -> chatSessionGrouper.group(loadSlackMessages());
            case WHATSAPP -> chatSessionGrouper.group(loadWhatsAppMessages());
        };
    }

    private List<SourceRecord> loadEmail() {
        List<SourceRecord> records = new ArrayList<>();
        for (JsonNode node : readJson(FIXTURE_ROOT + "gmail-sample.json")) {
            String from = node.get("from").asText();
            String date = node.get("date").asText();
            records.add(new SourceRecord(
                    SourceType.EMAIL,
                    node.get("tenantId").asText(),
                    "EMAIL|" + from + "|" + date,
                    Instant.parse(date),
                    from,
                    textOrNull(node.get("subject")),
                    textOrNull(node.get("body")),
                    node.toString(),
                    null));
        }
        return records;
    }

    private List<SourceRecord> loadDrive() {
        List<SourceRecord> records = new ArrayList<>();
        for (JsonNode node : readJson(FIXTURE_ROOT + "drive-sample.json")) {
            String owner = node.get("owners").get(0).get("emailAddress").asText();
            records.add(new SourceRecord(
                    SourceType.DRIVE_DOC,
                    node.get("tenantId").asText(),
                    node.get("id").asText(),
                    Instant.parse(node.get("modifiedTime").asText()),
                    owner,
                    textOrNull(node.get("name")),
                    textOrNull(node.get("description")),
                    node.toString(),
                    textOrNull(node.get("mimeType"))));
        }
        return records;
    }

    private List<SourceRecord> loadCalendar() {
        List<SourceRecord> records = new ArrayList<>();
        for (JsonNode node : readJson(FIXTURE_ROOT + "calendar-sample.json")) {
            String organizer = node.get("organizer").get("email").asText();
            String start = node.get("start").get("dateTime").asText();
            records.add(new SourceRecord(
                    SourceType.CALENDAR,
                    node.get("tenantId").asText(),
                    node.get("id").asText(),
                    Instant.parse(start),
                    organizer,
                    textOrNull(node.get("summary")),
                    textOrNull(node.get("description")),
                    node.toString(),
                    null));
        }
        return records;
    }

    private List<ChatMessage> loadSlackMessages() {
        List<ChatMessage> messages = new ArrayList<>();
        for (JsonNode node : readJson(FIXTURE_ROOT + "slack-sample.json")) {
            long epochMillis = Math.round(node.get("ts").asDouble() * 1000);
            messages.add(new ChatMessage(
                    SourceType.SLACK,
                    node.get("tenantId").asText(),
                    node.get("channel").asText(),
                    node.get("user").asText(),
                    Instant.ofEpochMilli(epochMillis),
                    node.get("text").asText()));
        }
        return messages;
    }

    private List<ChatMessage> loadWhatsAppMessages() {
        List<ChatMessage> messages = new ArrayList<>();
        try {
            Resource[] resources =
                    resourcePatternResolver.getResources("classpath:" + FIXTURE_ROOT + "whatsapp-*-sample.txt");
            for (Resource resource : resources) {
                String chatId = stripExtension(resource.getFilename());
                messages.addAll(parseWhatsAppFile(resource, chatId));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load WhatsApp fixtures", e);
        }
        return messages;
    }

    private List<ChatMessage> parseWhatsAppFile(Resource resource, String chatId) throws IOException {
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
                messages.add(new ChatMessage(
                        SourceType.WHATSAPP,
                        WHATSAPP_TENANT_ID,
                        chatId,
                        matcher.group(2),
                        localTimestamp.toInstant(ZoneOffset.UTC),
                        matcher.group(3)));
            }
        }
        return messages;
    }

    private JsonNode readJson(String classpathLocation) {
        try (InputStream in = new ClassPathResource(classpathLocation).getInputStream()) {
            return objectMapper.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load fixture: " + classpathLocation, e);
        }
    }

    private static String textOrNull(JsonNode node) {
        return (node == null || node.isNull()) ? null : node.asText();
    }

    private static String stripExtension(String filename) {
        if (filename == null) {
            return "unknown-chat";
        }
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
