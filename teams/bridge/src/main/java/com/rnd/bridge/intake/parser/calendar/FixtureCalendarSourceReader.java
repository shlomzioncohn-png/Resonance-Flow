package com.rnd.bridge.intake.parser.calendar;

import static com.rnd.bridge.intake.parser.FixtureIoSupport.FIXTURE_ROOT;
import static com.rnd.bridge.intake.parser.FixtureIoSupport.readJson;
import static com.rnd.bridge.intake.parser.FixtureIoSupport.textOrNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rnd.bridge.core.ReadCursor;
import com.rnd.bridge.intake.model.CalendarEventState;
import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.intake.service.CalendarEventStateReconciler;
import com.rnd.bridge.raw.model.ChangeType;
import com.rnd.bridge.raw.model.RawSourceType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Parses calendar-sample.json. Moved as-is from the old FixtureSourceLoader.loadCalendar() —
 * see plan.md for why this reads real Google Calendar API v3 Event resource field names.
 * Fixture events have no real Google etag, so node.toString() (the event's own JSON text) is
 * used as the etag surrogate passed to CalendarEventStateReconciler - it changes if and only if
 * the fixture content for that event actually changes, which is exactly the property a real
 * etag has (same content -> same marker -> a retry, not a new revision).
 */
@Component
@ConditionalOnProperty(
        prefix = "bridge.intake.calendar", name = "mode", havingValue = "fixture", matchIfMissing = true)
public class FixtureCalendarSourceReader implements CalendarSourceReader {

    private final ObjectMapper objectMapper;
    private final CalendarEventStateReconciler eventStateReconciler;

    public FixtureCalendarSourceReader(ObjectMapper objectMapper, CalendarEventStateReconciler eventStateReconciler) {
        this.objectMapper = objectMapper;
        this.eventStateReconciler = eventStateReconciler;
    }

    @Override
    public CalendarReadResult read(ReadCursor cursor) {
        List<SourceRecord> records = new ArrayList<>();
        for (JsonNode node : readJson(objectMapper, FIXTURE_ROOT + "calendar-sample.json")) {
            String tenantId = node.get("tenantId").asText();
            String eventId = node.get("id").asText();
            if (isCancelled(node)) {
                records.add(toCancelledSourceRecord(tenantId, eventId, node));
                continue;
            }
            String organizer = node.get("organizer").get("email").asText();
            String start = node.get("start").get("dateTime").asText();
            Instant occurredAt = Instant.parse(start);
            CalendarEventState state = eventStateReconciler.resolve(tenantId, eventId, node.toString());
            String correlationId = state.correlationId();

            JsonNode attachments = node.get("attachments");
            int attachmentCount = attachments == null ? 0 : attachments.size();

            records.add(new SourceRecord(
                    RawSourceType.CALENDAR, tenantId, eventId, correlationId, occurredAt, organizer,
                    List.of(organizer), textOrNull(node.get("summary")), textOrNull(node.get("description")),
                    node.toString().getBytes(StandardCharsets.UTF_8), null, null, 0, attachmentCount, List.of(),
                    state.revision()));

            if (attachments != null) {
                int index = 0;
                for (JsonNode attachment : attachments) {
                    index++;
                    byte[] content = Base64.getDecoder().decode(attachment.get("content").asText());
                    records.add(new SourceRecord(
                            RawSourceType.CALENDAR, tenantId, eventId + "|attachment-" + index, correlationId,
                            occurredAt, organizer, List.of(organizer), null, null, content,
                            textOrNull(attachment.get("mimeType")), textOrNull(attachment.get("filename")), index,
                            attachmentCount, List.of(), state.revision()));
                }
            }
        }
        // Fixtures never support incremental sync - no token to advance to.
        return new CalendarReadResult(records, null);
    }

    private static boolean isCancelled(JsonNode node) {
        JsonNode status = node.get("status");
        return status != null && "cancelled".equalsIgnoreCase(status.asText());
    }

    /** Fixture analog of RealCalendarSourceReader.toCancelledSourceRecord - see its javadoc for
     * why only id/etag-surrogate-derived fields are used, nothing organizer/summary/start. */
    private SourceRecord toCancelledSourceRecord(String tenantId, String eventId, JsonNode node) {
        CalendarEventState state = eventStateReconciler.resolve(tenantId, eventId, node.toString());
        return new SourceRecord(
                RawSourceType.CALENDAR, tenantId, eventId, state.correlationId(), Instant.now(), null, List.of(),
                null, null, node.toString().getBytes(StandardCharsets.UTF_8), null, null, 0, 0, List.of(),
                state.revision(), ChangeType.DELETE_REVIEW);
    }
}
