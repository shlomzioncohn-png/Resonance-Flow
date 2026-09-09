package com.rnd.bridge.intake.parser.email;

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
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Parses gmail-sample.json. Moved as-is from the old FixtureSourceLoader.loadEmail() — see
 * plan.md for why this reads real Gmail API field names.
 */
@Component
@ConditionalOnProperty(prefix = "bridge.intake.email", name = "mode", havingValue = "fixture", matchIfMissing = true)
public class FixtureEmailSourceReader implements EmailSourceReader {

    private final ObjectMapper objectMapper;

    public FixtureEmailSourceReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public List<SourceRecord> read() {
        List<SourceRecord> records = new ArrayList<>();
        for (JsonNode node : readJson(objectMapper, FIXTURE_ROOT + "gmail-sample.json")) {
            String from = node.get("from").asText();
            String date = node.get("date").asText();
            String tenantId = node.get("tenantId").asText();
            Instant occurredAt = Instant.parse(date);
            String naturalId = "EMAIL|" + from + "|" + date;
            String correlationId = UUID.randomUUID().toString();

            records.add(new SourceRecord(
                    RawSourceType.EMAIL, tenantId, naturalId, correlationId, occurredAt, from, List.of(from),
                    textOrNull(node.get("subject")), textOrNull(node.get("body")),
                    node.toString().getBytes(StandardCharsets.UTF_8), null, null, 0, List.of(), 0));

            JsonNode attachments = node.get("attachments");
            if (attachments != null) {
                int index = 0;
                for (JsonNode attachment : attachments) {
                    index++;
                    byte[] content = Base64.getDecoder().decode(attachment.get("content").asText());
                    records.add(new SourceRecord(
                            RawSourceType.EMAIL, tenantId, naturalId + "|attachment-" + index, correlationId,
                            occurredAt, from, List.of(from), null, null, content,
                            textOrNull(attachment.get("mimeType")), textOrNull(attachment.get("filename")), index,
                            List.of(), 0));
                }
            }
        }
        return records;
    }
}
