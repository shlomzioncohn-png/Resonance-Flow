package com.rnd.bridge.intake.parser.drive;

import static com.rnd.bridge.intake.parser.FixtureIoSupport.FIXTURE_ROOT;
import static com.rnd.bridge.intake.parser.FixtureIoSupport.readJson;
import static com.rnd.bridge.intake.parser.FixtureIoSupport.textOrNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.raw.model.RawSourceType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Parses drive-sample.json. Moved as-is from the old FixtureSourceLoader.loadDrive() — see
 * plan.md for why this reads real Google Drive API v3 File resource field names.
 */
@Component
@ConditionalOnProperty(prefix = "bridge.intake.drive", name = "mode", havingValue = "fixture", matchIfMissing = true)
public class FixtureDriveSourceReader implements DriveSourceReader {

    private final ObjectMapper objectMapper;

    public FixtureDriveSourceReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public List<SourceRecord> read() {
        List<SourceRecord> records = new ArrayList<>();
        for (JsonNode node : readJson(objectMapper, FIXTURE_ROOT + "drive-sample.json")) {
            String owner = node.get("owners").get(0).get("emailAddress").asText();
            records.add(new SourceRecord(
                    RawSourceType.DRIVE,
                    node.get("tenantId").asText(),
                    node.get("id").asText(),
                    UUID.randomUUID().toString(),
                    Instant.parse(node.get("modifiedTime").asText()),
                    owner,
                    List.of(owner),
                    textOrNull(node.get("name")),
                    textOrNull(node.get("description")),
                    node.toString().getBytes(StandardCharsets.UTF_8),
                    textOrNull(node.get("mimeType")),
                    null,
                    0,
                    0,
                    List.of(),
                    0));
        }
        return records;
    }
}
