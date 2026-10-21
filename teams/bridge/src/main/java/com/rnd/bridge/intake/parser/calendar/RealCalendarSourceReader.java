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
import com.rnd.bridge.intake.model.CalendarEventState;
import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.intake.service.CalendarEventStateReconciler;
import com.rnd.bridge.raw.model.ChangeType;
import com.rnd.bridge.raw.model.RawSourceType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Reads real Google Calendar events via events.list. Calendar attachments are Drive file
 * references, so fetching their bytes needs the Drive client - deferred until
 * RealDriveSourceReader exists (see plan.md); events land without their attachments for now.
 * setSingleEvents(true) expands recurring events into individual occurrences rather than
 * landing one record per recurrence rule.
 * cursor.syncToken() switches events.list to incremental mode (only events created/updated/
 * deleted since the last sync, regardless of how old the event itself is - unlike Slack's
 * "oldest", which filters by original send time and would miss an old event edited today).
 * A 410 response means the token expired server-side; the only recovery is a full re-sync -
 * readPages(null) does exactly that, and its resulting nextSyncToken (a fresh baseline, not
 * a continuation of the expired one) is returned as part of that same CalendarReadResult.
 * read()/readPages() return the nextSyncToken as part of their result rather than storing it in
 * an instance field - this class is a singleton Spring bean, and a shared mutable "last token"
 * field would let one concurrent read's token bleed into another's caller (two overlapping
 * events.list runs racing to overwrite the same field before either caller reads it back). By
 * returning the token as regular per-call data instead, each read()'s result is only ever
 * consumed by whichever caller made that call - there is nothing shared left to race on.
 * A cancelled event (event.getStatus() == "cancelled", the tombstone Google's incremental sync
 * emits for a deleted/cancelled event) is detected in toSourceRecord before any of the normal
 * mapping below touches organizer/summary/attendees/start - Google does not guarantee those
 * fields are populated on a cancellation notice, so accessing them there would risk an NPE.
 * See toCancelledSourceRecord for the sparse mapping used instead - identity/revision still
 * come from the same CalendarEventStateReconciler.resolve call (it has no notion of
 * cancellation, only etag changes), so a cancellation is just another revision like any edit;
 * only the resulting SourceRecord's changeType (DELETE_REVIEW instead of UPSERT) marks it as one.
 */
@Component
@ConditionalOnProperty(prefix = "bridge.intake.calendar", name = "mode", havingValue = "real")
public class RealCalendarSourceReader implements CalendarSourceReader {

    private final GoogleCredentialsProvider credentialsProvider;
    private final CalendarImportProperties properties;
    private final CalendarEventStateReconciler eventStateReconciler;
    private Calendar client;

    @Autowired
    public RealCalendarSourceReader(
            GoogleCredentialsProvider credentialsProvider, CalendarImportProperties properties,
            CalendarEventStateReconciler eventStateReconciler) {
        this.credentialsProvider = credentialsProvider;
        this.properties = properties;
        this.eventStateReconciler = eventStateReconciler;
    }

    /** Test-only seam: lets tests inject a mocked Calendar client instead of the real one
     * client() would otherwise lazily build from live Google credentials. */
    RealCalendarSourceReader(
            GoogleCredentialsProvider credentialsProvider, CalendarImportProperties properties,
            CalendarEventStateReconciler eventStateReconciler, Calendar client) {
        this(credentialsProvider, properties, eventStateReconciler);
        this.client = client;
    }

    @Override
    public CalendarReadResult read(ReadCursor cursor) {
        String syncToken = cursor == null ? null : cursor.token();
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

    private CalendarReadResult readPages(String syncToken) throws IOException {
        Calendar calendar = client();
        List<SourceRecord> records = new ArrayList<>();
        String pageToken = null;
        // nextSyncToken only ever appears on the last page of a sync (Google's contract: it's
        // absent whenever nextPageToken is present) - so it's only ever read once, right before
        // the loop ends.
        String nextSyncToken = null;
        do {
            Events page = listPage(calendar, pageToken, syncToken);
            for (Event event : page.getItems()) {
                records.add(toSourceRecord(event));
            }
            pageToken = page.getNextPageToken();
            if (pageToken == null) {
                nextSyncToken = page.getNextSyncToken();
            }
        } while (pageToken != null);
        // records/nextSyncToken are purely local to this call - returned together, never stashed
        // anywhere shared (see class javadoc).
        return new CalendarReadResult(records, nextSyncToken);
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
        if (isCancelled(event)) {
            return toCancelledSourceRecord(event);
        }
        String organizer = event.getOrganizer().getEmail();
        Instant occurredAt = startInstant(event.getStart());
        // correlationId/revision come from persisted CalendarEventState, not a fresh UUID/0
        // every read - see CalendarEventStateReconciler for the first-seen/retry/updated
        // decision, keyed on event.getId() (stable) and event.getEtag() (Google's per-version
        // change marker).
        CalendarEventState state = eventStateReconciler.resolve(properties.tenantId(), event.getId(), event.getEtag());
        // Calendar attachments are deferred until RealDriveSourceReader exists (see class
        // javadoc) - events land without them for now, so attachmentCount is always 0 here.
        return new SourceRecord(
                RawSourceType.CALENDAR, properties.tenantId(), event.getId(), state.correlationId(),
                occurredAt, organizer, participants(event, organizer), event.getSummary(), event.getDescription(),
                event.toString().getBytes(StandardCharsets.UTF_8), null, null, 0, 0, List.of(), state.revision())
                .withSourceRef(event.getId());
    }

    private static boolean isCancelled(Event event) {
        return "cancelled".equalsIgnoreCase(event.getStatus());
    }

    /**
     * A cancelled event's SourceRecord uses only fields Google actually guarantees on a
     * cancellation notice - id, etag, and (usually) updated; organizer/summary/description/
     * attendees/start are routinely absent and must never be dereferenced here. sender/
     * participants/subject/body are left null/empty rather than fabricated - there is no
     * content to report, only the fact that this event no longer exists.
     * occurredAt falls back to Instant.now() only if Google omits `updated` too (defensive -
     * not expected in practice), since SourceRecord.occurredAt has no legitimate "unknown" value.
     * rawContent is event.toString() - Google's own (sparse) JSON for this notice, not
     * fabricated content, landed as-is like every other source's raw bytes.
     * correlationId/revision still come from CalendarEventStateReconciler.resolve, exactly like
     * a normal edit (see class javadoc) - only changeType marks this as a cancellation.
     */
    private SourceRecord toCancelledSourceRecord(Event event) {
        CalendarEventState state = eventStateReconciler.resolve(properties.tenantId(), event.getId(), event.getEtag());
        Instant occurredAt = event.getUpdated() != null
                ? Instant.ofEpochMilli(event.getUpdated().getValue())
                : Instant.now();
        return new SourceRecord(
                RawSourceType.CALENDAR, properties.tenantId(), event.getId(), state.correlationId(),
                occurredAt, null, List.of(), null, null,
                event.toString().getBytes(StandardCharsets.UTF_8), null, null, 0, 0, List.of(), state.revision(),
                ChangeType.DELETE_REVIEW).withSourceRef(event.getId());
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

