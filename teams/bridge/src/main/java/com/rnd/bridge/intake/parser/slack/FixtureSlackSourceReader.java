package com.rnd.bridge.intake.parser.slack;

import static com.rnd.bridge.intake.parser.FixtureIoSupport.FIXTURE_ROOT;
import static com.rnd.bridge.intake.parser.FixtureIoSupport.readAttachmentFile;
import static com.rnd.bridge.intake.parser.FixtureIoSupport.readJson;
import static com.rnd.bridge.intake.parser.FixtureIoSupport.textOrNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.bridge.intake.model.ChatMessage;
import com.rnd.bridge.raw.model.RawSourceType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Parses slack-sample.json. Moved as-is from the old FixtureSourceLoader.loadSlackMessages()
 * — see plan.md for why this reads a real Slack export message object shape.
 */
@Component
@ConditionalOnProperty(prefix = "bridge.intake.slack", name = "mode", havingValue = "fixture", matchIfMissing = true)
public class FixtureSlackSourceReader implements SlackSourceReader {

    private final ObjectMapper objectMapper;

    public FixtureSlackSourceReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public List<ChatMessage> read() {
        List<ChatMessage> messages = new ArrayList<>();
        for (JsonNode node : readJson(objectMapper, FIXTURE_ROOT + "slack-sample.json")) {
            long epochMillis = Math.round(node.get("ts").asDouble() * 1000);
            messages.add(new ChatMessage(
                    RawSourceType.SLACK,
                    node.get("tenantId").asText(),
                    node.get("channel").asText(),
                    node.get("user").asText(),
                    Instant.ofEpochMilli(epochMillis),
                    node.get("text").asText(),
                    resolveAttachments(node.get("files"))));
        }
        return messages;
    }

    private List<ChatMessage.Attachment> resolveAttachments(JsonNode files) {
        if (files == null) {
            return List.of();
        }
        List<ChatMessage.Attachment> attachments = new ArrayList<>();
        for (JsonNode file : files) {
            String filename = file.get("name").asText();
            String mimeType = textOrNull(file.get("mimetype"));
            attachments.add(readAttachmentFile(filename, mimeType));
        }
        return attachments;
    }
}
