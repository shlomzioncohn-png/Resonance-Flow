package com.rnd.bridge.intake.parser.calendar;

import static com.rnd.bridge.intake.parser.FixtureIoSupport.FIXTURE_ROOT;
import static com.rnd.bridge.intake.parser.FixtureIoSupport.readJson;
import static com.rnd.bridge.intake.parser.FixtureIoSupport.textOrNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.bridge.core.ReadCursor;
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
 * Parses calendar-sample.json. Moved as-is from the old FixtureSourceLoader.loadCalendar() —
 * see plan.md for why this reads real Google Calendar API v3 Event resource field names.
 */
@Component
@ConditionalOnProperty(
        prefix = "bridge.intake.calendar", name = "mode", havingValue = "fixture", matchIfMissing = true)
public class FixtureCalendarSourceReader implements CalendarSourceReader {

    private final ObjectMapper objectMapper;

    public FixtureCalendarSourceReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public List<SourceRecord> read(ReadCursor cursor) {
        List<SourceRecord> records = new ArrayList<>();
        for (JsonNode node : readJson(objectMapper, FIXTURE_ROOT + "calendar-sample.json")) {
            String organizer = node.get("organizer").get("email").asText();
            String start = node.get("start").get("dateTime").asText();
            String tenantId = node.get("tenantId").asText();
            String eventId = node.get("id").asText();
            Instant occurredAt = Instant.parse(start);
            String correlationId = UUID.randomUUID().toString();

            records.add(new SourceRecord(
                    RawSourceType.CALENDAR, tenantId, eventId, correlationId, occurredAt, organizer,
                    List.of(organizer), textOrNull(node.get("summary")), textOrNull(node.get("description")),
                    node.toString().getBytes(StandardCharsets.UTF_8), null, null, 0, List.of(), 0));

            JsonNode attachments = node.get("attachments");
            if (attachments != null) {
                int index = 0;
                for (JsonNode attachment : attachments) {
                    index++;
                    byte[] content = Base64.getDecoder().decode(attachment.get("content").asText());
                    records.add(new SourceRecord(
                            RawSourceType.CALENDAR, tenantId, eventId + "|attachment-" + index, correlationId,
                            occurredAt, organizer, List.of(organizer), null, null, content,
                            textOrNull(attachment.get("mimeType")), textOrNull(attachment.get("filename")), index,
                            List.of(), 0));
                }
            }
        }
        return records;
    }
}
