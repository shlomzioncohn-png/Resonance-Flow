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
 * Parses a Zoom-shaped meeting-metadata sidecar - JSON matching Zoom's documented
 * Dashboard/Report API field names (see fixtures/media/zoom-meeting-metadata.sample.json).
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
                        instantOrNull(p.get("leave_time"))));
            }
        }

        return new MeetingMetadata(
                textOrNull(root.get("id")),
                textOrNull(root.get("topic")),
                textOrNull(root.get("host_name")),
                instantOrNull(root.get("start_time")),
                instantOrNull(root.get("end_time")),
                participants);
    }

    private String textOrNull(JsonNode node) {
        return (node == null || node.isNull()) ? null : node.asText();
    }

    private Instant instantOrNull(JsonNode node) {
        return (node == null || node.isNull()) ? null : Instant.parse(node.asText());
    }
}
