package com.rnd.bridge.intake.parser.calendar;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.CalendarScopes;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventAttendee;
import com.google.api.services.calendar.model.EventDateTime;
import com.google.api.services.calendar.model.Events;
import com.rnd.bridge.core.ReadCursor;
import com.rnd.bridge.intake.config.CalendarImportProperties;
import com.rnd.bridge.intake.google.GoogleCredentialsProvider;
import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.raw.model.RawSourceType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Reads real Google Calendar events via events.list. Calendar attachments are Drive file
 * references, so fetching their bytes needs the Drive client - deferred until
 * RealDriveSourceReader exists (see plan.md); events land without their attachments for now.
 * setSingleEvents(true) expands recurring events into individual occurrences rather than
 * landing one record per recurrence rule.
 * cursor.syncToken() switches events.list to incremental mode (only events created/updated/
 * deleted since the last sync, regardless of how old the event itself is - unlike Slack's
 * "oldest", which filters by original send time and would miss an old event edited today).
 * A 410 response means the token expired server-side; the only recovery is a full re-sync.
 */
@Component
@ConditionalOnProperty(prefix = "bridge.intake.calendar", name = "mode", havingValue = "real")
public class RealCalendarSourceReader implements CalendarSourceReader {

    private final GoogleCredentialsProvider credentialsProvider;
    private final CalendarImportProperties properties;
    private Calendar client;

    public RealCalendarSourceReader(GoogleCredentialsProvider credentialsProvider, CalendarImportProperties properties) {
        this.credentialsProvider = credentialsProvider;
        this.properties = properties;
    }

    @Override
    public List<SourceRecord> read(ReadCursor cursor) {
        String syncToken = cursor == null ? null : cursor.syncToken();
        try {
            return readPages(syncToken);
        } catch (GoogleJsonResponseException e) {
            if (e.getStatusCode() == 410 && syncToken != null) {
                // Expired syncToken - the only way back is a full sync from scratch.
                try {
                    return readPages(null);
                } catch (IOException retryFailure) {
                    throw new UncheckedIOException(
                            "Failed to list Calendar events for calendar " + properties.calendarId(), retryFailure);
                }
            }
            throw new UncheckedIOException(
                    "Failed to list Calendar events for calendar " + properties.calendarId(), e);
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Failed to list Calendar events for calendar " + properties.calendarId(), e);
        }
    }

    private List<SourceRecord> readPages(String syncToken) throws IOException {
        Calendar calendar = client();
        List<SourceRecord> records = new ArrayList<>();
        String pageToken = null;
        do {
            Events page = listPage(calendar, pageToken, syncToken);
            for (Event event : page.getItems()) {
                records.add(toSourceRecord(event));
            }
            pageToken = page.getNextPageToken();
        } while (pageToken != null);
        return records;
    }

    private Events listPage(Calendar calendar, String pageToken, String syncToken) throws IOException {
        Calendar.Events.List request = calendar.events().list(properties.calendarId())
                .setSingleEvents(true)
                .setPageToken(pageToken);
        if (syncToken != null) {
            // orderBy/timeMin/timeMax and a few others can't be combined with syncToken -
            // Google rejects the request outright if they are.
            request.setSyncToken(syncToken);
        } else {
            request.setOrderBy("startTime");
        }
        return request.execute();
    }

    private synchronized Calendar client() {
        if (client == null) {
            // Plain NetHttpTransport, not GoogleNetHttpTransport.newTrustedTransport() - see
            // GoogleCredentialsProvider for why (system truststore vs. Google's bundled one).
            HttpTransport transport = new NetHttpTransport();
            var credential = credentialsProvider.credentials(List.of(CalendarScopes.CALENDAR_READONLY));
            client = new Calendar.Builder(transport, GsonFactory.getDefaultInstance(), credential)
                    .setApplicationName("bridge-intake")
                    .build();
        }
        return client;
    }

    private SourceRecord toSourceRecord(Event event) {
        String organizer = event.getOrganizer().getEmail();
        Instant occurredAt = startInstant(event.getStart());
        return new SourceRecord(
                RawSourceType.CALENDAR, properties.tenantId(), event.getId(), UUID.randomUUID().toString(),
                occurredAt, organizer, participants(event, organizer), event.getSummary(), event.getDescription(),
                event.toString().getBytes(StandardCharsets.UTF_8), null, null, 0, List.of(), 0);
    }

    /** Organizer plus every attendee - the Calendar equivalent of an email's To/Cc list. */
    private static List<String> participants(Event event, String organizer) {
        List<String> participants = new ArrayList<>();
        participants.add(organizer);
        if (event.getAttendees() != null) {
            for (EventAttendee attendee : event.getAttendees()) {
                participants.add(attendee.getEmail());
            }
        }
        return participants;
    }

    private static Instant startInstant(EventDateTime start) {
        DateTime dateTime = start.getDateTime() != null ? start.getDateTime() : start.getDate();
        return Instant.ofEpochMilli(dateTime.getValue());
    }
}
