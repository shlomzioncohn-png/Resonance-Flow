package com.rnd.bridge.media.meeting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Parses a Zoom-shaped meeting-metadata sidecar - JSON matching Zoom's official OpenAPI spec
 * field names for GET /report/meetings/{meetingId} (meeting-level) and GET
 * /report/meetings/{meetingId}/participants (participants array), combined into one payload
 * (see fixtures/media/zoom-meeting-metadata.sample.json). Verified directly against that spec
 * document, not from memory.
 * <p>
 * Note: the report's top-level "host" field is named user_email (not host_email or host_name -
 * the report attributes the meeting to a "user", and only returns their email, not a display
 * name).
 * <p>
 * No Real* counterpart yet - real Zoom API wiring needs a meetingId this lane's current
 * manual-folder trigger doesn't have (see plan). Fixture-only, same as the sample
 * MediaIntakeOrchestrator ships with today.
 */
@Component
public class FixtureZoomMeetingMetadataReader implements MeetingMetadataReader {

    private final ObjectMapper objectMapper;

    public FixtureZoomMeetingMetadataReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public MeetingMetadata read(Path sidecarFile) throws IOException {
        JsonNode root;
        try (var in = Files.newInputStream(sidecarFile)) {
            root = objectMapper.readTree(in);
        }

        List<ParticipantActivity> participants = new ArrayList<>();
        JsonNode participantsNode = root.get("participants");
        if (participantsNode != null) {
            for (JsonNode p : participantsNode) {
                participants.add(new ParticipantActivity(
                        textOrNull(p.get("id")),
                        textOrNull(p.get("name")),
                        instantOrNull(p.get("join_time")),
                        instantOrNull(p.get("leave_time")),
                        textOrNull(p.get("user_id")),
                        textOrNull(p.get("status")),
                        intOrNull(p.get("duration")),
                        boolOrNull(p.get("failover")),
                        textOrNull(p.get("customer_key")),
                        textOrNull(p.get("registrant_id")),
                        textOrNull(p.get("bo_mtg_id"))));
            }
        }

        return new MeetingMetadata(
                textOrNull(root.get("id")),
                textOrNull(root.get("topic")),
                textOrNull(root.get("user_email")),
                instantOrNull(root.get("start_time")),
                instantOrNull(root.get("end_time")),
                participants,
                textOrNull(root.get("uuid")),
                textOrNull(root.get("dept")),
                intOrNull(root.get("duration")),
                intOrNull(root.get("total_minutes")),
                intOrNull(root.get("participants_count")),
                intOrNull(root.get("type")),
                customFields(root.get("custom_keys"), "key"),
                customFields(root.get("tracking_fields"), "field"));
    }

    private List<MeetingMetadata.CustomField> customFields(JsonNode arrayNode, String keyFieldName) {
        List<MeetingMetadata.CustomField> fields = new ArrayList<>();
        if (arrayNode != null) {
            for (JsonNode f : arrayNode) {
                fields.add(new MeetingMetadata.CustomField(
                        textOrNull(f.get(keyFieldName)), textOrNull(f.get("value"))));
            }
        }
        return fields;
    }

    private String textOrNull(JsonNode node) {
        return (node == null || node.isNull()) ? null : node.asText();
    }

    private Instant instantOrNull(JsonNode node) {
        return (node == null || node.isNull()) ? null : Instant.parse(node.asText());
    }

    private Integer intOrNull(JsonNode node) {
        return (node == null || node.isNull()) ? null : node.asInt();
    }

    private Boolean boolOrNull(JsonNode node) {
        return (node == null || node.isNull()) ? null : node.asBoolean();
    }
}
