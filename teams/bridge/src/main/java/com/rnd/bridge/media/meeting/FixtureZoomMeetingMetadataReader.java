package com.rnd.bridge.media.meeting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Field-by-field, not all-or-nothing: only Files.newInputStream/objectMapper.readTree (the
 * sidecar isn't valid JSON at all) throw and fail the whole read. Once we have a JSON tree, a
 * single malformed field (e.g. an unparsable start_time) is caught and logged, degrading just
 * that field to null rather than losing participants and everything else too -
 * MediaIntakeOrchestrator still lands the sidecar as-is either way, so a partial parse here
 * only affects what ends up on the RawObjectEvent, never what's in MinIO.
 * <p>
 * No Real* counterpart yet - real Zoom API wiring needs a meetingId this lane's current
 * manual-folder trigger doesn't have (see plan). Fixture-only, same as the sample
 * MediaIntakeOrchestrator ships with today.
 */
@Component
public class FixtureZoomMeetingMetadataReader implements MeetingMetadataReader {

    private static final Logger log = LoggerFactory.getLogger(FixtureZoomMeetingMetadataReader.class);

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
                try {
                    participants.add(new ParticipantActivity(
                            textOrNull(p.get("id")),
                            textOrNull(p.get("name")),
                            instantOrNull(p.get("join_time"), sidecarFile, "participants[].join_time"),
                            instantOrNull(p.get("leave_time"), sidecarFile, "participants[].leave_time"),
                            textOrNull(p.get("user_id")),
                            textOrNull(p.get("status")),
                            intOrNull(p.get("duration"), sidecarFile, "participants[].duration"),
                            boolOrNull(p.get("failover")),
                            textOrNull(p.get("customer_key")),
                            textOrNull(p.get("registrant_id")),
                            textOrNull(p.get("bo_mtg_id"))));
                } catch (RuntimeException e) {
                    // One malformed participant shouldn't cost us the rest of the list -
                    // skip just this entry, same "degrade, don't discard" reasoning as the
                    // meeting-level fields below.
                    log.warn("Skipping unparsable participant entry in {}", sidecarFile, e);
                }
            }
        }

        return new MeetingMetadata(
                textOrNull(root.get("id")),
                textOrNull(root.get("topic")),
                textOrNull(root.get("user_email")),
                instantOrNull(root.get("start_time"), sidecarFile, "start_time"),
                instantOrNull(root.get("end_time"), sidecarFile, "end_time"),
                participants,
                textOrNull(root.get("uuid")),
                textOrNull(root.get("dept")),
                intOrNull(root.get("duration"), sidecarFile, "duration"),
                intOrNull(root.get("total_minutes"), sidecarFile, "total_minutes"),
                intOrNull(root.get("participants_count"), sidecarFile, "participants_count"),
                intOrNull(root.get("type"), sidecarFile, "type"),
                customFields(root.get("custom_keys"), "key"),
                customFields(root.get("tracking_fields"), "field"),
                null);
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

    private Instant instantOrNull(JsonNode node, Path sidecarFile, String fieldName) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            return Instant.parse(node.asText());
        } catch (DateTimeParseException e) {
            log.warn("Unparsable timestamp for {} in {} - leaving it null: {}",
                    fieldName, sidecarFile, node.asText());
            return null;
        }
    }

    private Integer intOrNull(JsonNode node, Path sidecarFile, String fieldName) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isNumber() && !node.canConvertToInt()) {
            log.warn("Non-numeric value for {} in {} - leaving it null: {}",
                    fieldName, sidecarFile, node.asText());
            return null;
        }
        return node.asInt();
    }

    private Boolean boolOrNull(JsonNode node) {
        return (node == null || node.isNull()) ? null : node.asBoolean();
    }
}
