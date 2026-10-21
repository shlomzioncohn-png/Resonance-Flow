package com.rnd.bridge.intake.parser.calendar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpResponseException;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import com.google.api.services.calendar.model.Events;
import com.rnd.bridge.core.ReadCursor;
import com.rnd.bridge.intake.config.CalendarImportProperties;
import com.rnd.bridge.intake.lane.CalendarEventStateStore;
import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.intake.service.CalendarEventStateReconciler;
import com.rnd.bridge.storage.ControlPlaneStateStores;
import com.rnd.bridge.storage.InMemoryObjectStorageClient;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Covers the syncToken lifecycle in RealCalendarSourceReader.readPages/read: full vs.
 * incremental request shape, pagination via nextPageToken, nextSyncToken capture from the final
 * page only, and 410 (expired token) recovery - plus the concurrency property the
 * CalendarReadResult-per-call design exists for: two overlapping reads against the same
 * singleton reader instance can never see each other's token, because there is no shared field
 * either could overwrite (see CalendarReadResult/RealCalendarSourceReader's javadoc).
 * Doesn't cover what UnstructuredIntakeController does with the result afterward - that's
 * UnstructuredIntakeControllerTest's job (safe advancement/freezing on failure).
 * Calendar/Calendar.Events/Calendar.Events.List are mocked (Mockito.RETURNS_SELF for the
 * fluent set* chain, since AbstractGoogleClientRequest's setters return "this") because
 * Calendar.Events.List.execute() would otherwise make a real HTTP call; Events/Event/
 * EventDateTime/Event.Organizer are used as plain POJOs since they're just GenericJson data
 * classes with no I/O.
 */
class RealCalendarSourceReaderTest {

    private static final CalendarImportProperties PROPERTIES = new CalendarImportProperties("primary", "kamatech");

    private final Calendar calendar = mock(Calendar.class);
    private final Calendar.Events events = mock(Calendar.Events.class);

    private RealCalendarSourceReader reader() {
        when(calendar.events()).thenReturn(events);
        CalendarEventStateReconciler eventStateReconciler = new CalendarEventStateReconciler(
                new CalendarEventStateStore(
                        new InMemoryObjectStorageClient(), ControlPlaneStateStores.inMemory(), new ObjectMapper()));
        return new RealCalendarSourceReader(null, PROPERTIES, eventStateReconciler, calendar);
    }

    private Calendar.Events.List mockRequest() {
        return mock(Calendar.Events.List.class, Mockito.RETURNS_SELF);
    }

    private Event event(String id) {
        return new Event()
                .setId(id)
                .setOrganizer(new Event.Organizer().setEmail("malki@kamatech.org"))
                .setStart(new EventDateTime().setDateTime(new DateTime(1_700_000_000_000L)))
                .setSummary("summary");
    }

    @Test
    void fullSyncNeverSetsASyncTokenAndCapturesTheReturnedNextSyncTokenInTheResult() throws IOException {
        Calendar.Events.List request = mockRequest();
        when(events.list("primary")).thenReturn(request);
        Events page = new Events().setItems(List.of(event("e1"))).setNextSyncToken("sync-token-1");
        when(request.execute()).thenReturn(page);

        CalendarReadResult result = reader().read(ReadCursor.full());

        assertThat(result.records()).hasSize(1);
        assertThat(result.nextSyncToken()).isEqualTo("sync-token-1");
        verify(request, never()).setSyncToken(any());
        verify(request).setOrderBy("startTime");
    }

    @Test
    void incrementalSyncPassesTheStoredTokenAndNeverSetsOrderBy() throws IOException {
        Calendar.Events.List request = mockRequest();
        when(events.list("primary")).thenReturn(request);
        Events page = new Events().setItems(List.of()).setNextSyncToken("sync-token-2");
        when(request.execute()).thenReturn(page);

        CalendarReadResult result = reader().read(new ReadCursor(null, "sync-token-1"));

        assertThat(result.nextSyncToken()).isEqualTo("sync-token-2");
        verify(request).setSyncToken("sync-token-1");
        verify(request, never()).setOrderBy(any());
    }

    @Test
    void paginationFollowsNextPageTokenAndOnlyTheFinalPageContributesTheSyncToken() throws IOException {
        Calendar.Events.List page1Request = mockRequest();
        Calendar.Events.List page2Request = mockRequest();
        when(events.list("primary")).thenReturn(page1Request, page2Request);

        Events page1 = new Events().setItems(List.of(event("e1"))).setNextPageToken("page-token-1");
        // A non-final page must never carry nextSyncToken per Google's contract, but even if it
        // did, readPages must not pick it up - only the page where nextPageToken is absent counts.
        Events page2 = new Events().setItems(List.of(event("e2"))).setNextSyncToken("sync-token-final");
        when(page1Request.execute()).thenReturn(page1);
        when(page2Request.execute()).thenReturn(page2);

        CalendarReadResult result = reader().read(ReadCursor.full());

        assertThat(result.records()).hasSize(2);
        verify(page2Request).setPageToken("page-token-1");
        assertThat(result.nextSyncToken()).isEqualTo("sync-token-final");
    }

    @Test
    void expiredSyncTokenTriggersAFullResyncAndReturnsTheFreshToken() throws IOException {
        Calendar.Events.List incrementalRequest = mockRequest();
        Calendar.Events.List fullResyncRequest = mockRequest();
        when(events.list("primary")).thenReturn(incrementalRequest, fullResyncRequest);

        GoogleJsonResponseException expired = expiredTokenException();
        when(incrementalRequest.execute()).thenThrow(expired);
        Events freshPage = new Events().setItems(List.of(event("e1"))).setNextSyncToken("sync-token-fresh");
        when(fullResyncRequest.execute()).thenReturn(freshPage);

        CalendarReadResult result = reader().read(new ReadCursor(null, "expired-token"));

        assertThat(result.records()).hasSize(1);
        verify(incrementalRequest).setSyncToken("expired-token");
        // The recovery request is a full sync, not a continuation of the expired token.
        verify(fullResyncRequest, never()).setSyncToken(any());
        verify(fullResyncRequest).setOrderBy("startTime");
        assertThat(result.nextSyncToken()).isEqualTo("sync-token-fresh");
    }

    @Test
    void aNonExpiryFailureIsNotRetried() throws IOException {
        Calendar.Events.List request = mockRequest();
        when(events.list("primary")).thenReturn(request);
        doThrow(new IOException("network blip")).when(request).execute();

        RealCalendarSourceReader reader = reader();

        assertThatThrownBy(() -> reader.read(new ReadCursor(null, "sync-token-1")))
                .isInstanceOf(UncheckedIOException.class);
        verify(events, Mockito.times(1)).list("primary");
    }

    /**
     * The concurrency property the CalendarReadResult design exists for: two reads overlapping
     * in time against the SAME singleton reader instance (as would happen if two HTTP /run
     * requests raced each other in production - see UnstructuredIntakeController) must each get
     * back exactly their own token, never the other's. Forces the overlap explicitly (both
     * requests' execute() blocks until both are in flight, then both are released together) so
     * this isn't just passing by lucky scheduling - with the old shared lastSyncToken field,
     * this interleaving was exactly the scenario where one call's result could clobber the
     * other's before its own caller read it back. With the field removed entirely, there is
     * nothing left for either call to race on: each thread's token comes back as part of its
     * own return value.
     */
    @Test
    void concurrentReadsNeverCrossContaminateTheirSyncTokens() throws Exception {
        Calendar.Events.List requestA = mockRequest();
        Calendar.Events.List requestB = mockRequest();
        // Explicit, race-free assignment of which logical call gets which backing request -
        // deliberately not relying on Mockito's own consecutive-thenReturn bookkeeping being
        // safe under concurrent invocation from two threads.
        AtomicInteger callCount = new AtomicInteger();
        when(events.list("primary")).thenAnswer(
                invocation -> callCount.getAndIncrement() == 0 ? requestA : requestB);

        CountDownLatch bothInFlight = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        when(requestA.execute()).thenAnswer(invocation -> {
            bothInFlight.countDown();
            release.await();
            return new Events().setItems(List.of(event("a1"))).setNextSyncToken("token-A");
        });
        when(requestB.execute()).thenAnswer(invocation -> {
            bothInFlight.countDown();
            release.await();
            return new Events().setItems(List.of(event("b1"))).setNextSyncToken("token-B");
        });

        RealCalendarSourceReader reader = reader();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<CalendarReadResult> futureA = executor.submit(() -> reader.read(ReadCursor.full()));
            Future<CalendarReadResult> futureB = executor.submit(() -> reader.read(ReadCursor.full()));

            assertThat(bothInFlight.await(5, TimeUnit.SECONDS)).isTrue(); // both truly overlapping
            release.countDown(); // let both complete at (as close as possible to) the same instant

            CalendarReadResult resultA = futureA.get(5, TimeUnit.SECONDS);
            CalendarReadResult resultB = futureB.get(5, TimeUnit.SECONDS);

            // Regardless of which physical request each logical call happened to receive, each
            // call's own result must carry exactly its own token - never a mix, never the other's.
            assertThat(Set.of(resultA.nextSyncToken(), resultB.nextSyncToken()))
                    .containsExactlyInAnyOrder("token-A", "token-B");
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Proves toSourceRecord actually wires CalendarEventStateReconciler's output onto the
     * SourceRecord: a genuinely changed event (different etag on the second read) keeps the same
     * correlationId but its revision advances - the mapping-level counterpart to
     * CalendarEventStateReconcilerTest's own unit coverage of resolve() itself.
     */
    @Test
    void toSourceRecordCarriesTheReconciledCorrelationIdAndRevision() throws IOException {
        Calendar.Events.List firstRequest = mockRequest();
        Calendar.Events.List secondRequest = mockRequest();
        when(events.list("primary")).thenReturn(firstRequest, secondRequest);
        when(firstRequest.execute()).thenReturn(new Events()
                .setItems(List.of(event("evt-1").setEtag("etag-1"))).setNextSyncToken("sync-token-1"));
        when(secondRequest.execute()).thenReturn(new Events()
                .setItems(List.of(event("evt-1").setEtag("etag-2"))).setNextSyncToken("sync-token-2"));

        RealCalendarSourceReader reader = reader();
        SourceRecord first = reader.read(ReadCursor.full()).records().get(0);
        SourceRecord second = reader.read(new ReadCursor(null, "sync-token-1")).records().get(0);

        assertThat(first.revision()).isZero();
        assertThat(second.revision()).isEqualTo(1);
        assertThat(second.correlationId()).isEqualTo(first.correlationId());
        assertThat(first.changeType()).isEqualTo(com.rnd.bridge.raw.model.ChangeType.UPSERT);
        assertThat(second.changeType()).isEqualTo(com.rnd.bridge.raw.model.ChangeType.UPSERT);
    }

    private Event cancelledEvent(String id, String etag) {
        // Deliberately does NOT set organizer/summary/description/attendees/start - Google does
        // not guarantee any of those are populated on a cancellation notice (see
        // RealCalendarSourceReader.toCancelledSourceRecord's javadoc).
        return new Event().setId(id).setStatus("cancelled").setEtag(etag);
    }

    @Test
    void cancelledEventForAnExistingEventReusesCorrelationIdIncrementsRevisionAndIsDeleteReview() throws IOException {
        Calendar.Events.List firstRequest = mockRequest();
        Calendar.Events.List secondRequest = mockRequest();
        when(events.list("primary")).thenReturn(firstRequest, secondRequest);
        when(firstRequest.execute()).thenReturn(new Events()
                .setItems(List.of(event("evt-1").setEtag("etag-1"))).setNextSyncToken("sync-token-1"));
        when(secondRequest.execute()).thenReturn(new Events()
                .setItems(List.of(cancelledEvent("evt-1", "etag-cancelled"))).setNextSyncToken("sync-token-2"));

        RealCalendarSourceReader reader = reader();
        SourceRecord original = reader.read(ReadCursor.full()).records().get(0);
        SourceRecord cancelled = reader.read(new ReadCursor(null, "sync-token-1")).records().get(0);

        assertThat(cancelled.correlationId()).isEqualTo(original.correlationId());
        assertThat(cancelled.revision()).isEqualTo(1);
        assertThat(cancelled.changeType()).isEqualTo(com.rnd.bridge.raw.model.ChangeType.DELETE_REVIEW);
    }

    /**
     * A cancellation notice from Google routinely omits organizer/summary/description/
     * attendees/start entirely - toSourceRecord must detect status=="cancelled" and branch away
     * from the normal mapping path before any of those fields are dereferenced, or this throws
     * a NullPointerException instead of producing a record.
     */
    @Test
    void cancelledEventMappingDoesNotRequireOrganizerSummaryAttendeesOrStart() throws IOException {
        Calendar.Events.List request = mockRequest();
        when(events.list("primary")).thenReturn(request);
        when(request.execute()).thenReturn(new Events()
                .setItems(List.of(cancelledEvent("evt-sparse", "etag-1"))).setNextSyncToken("sync-token-1"));

        List<SourceRecord> records = reader().read(ReadCursor.full()).records();

        assertThat(records).hasSize(1);
        SourceRecord record = records.get(0);
        assertThat(record.naturalId()).isEqualTo("evt-sparse");
        assertThat(record.changeType()).isEqualTo(com.rnd.bridge.raw.model.ChangeType.DELETE_REVIEW);
        assertThat(record.sender()).isNull();
        assertThat(record.subject()).isNull();
        assertThat(record.participants()).isEmpty();
        assertThat(record.rawContent()).isNotEmpty();
    }

    /** Cancellation retry semantics, mirroring incrementalSyncPassesTheStoredTokenAndNeverSetsOrderBy's
     * sibling coverage in CalendarEventStateReconcilerTest, but exercised through the full
     * toSourceRecord mapping path this time. */
    @Test
    void retryOfTheSameCancelledEtagAfterAFailedPublishReusesTheSameRevision() throws IOException {
        Calendar.Events.List firstRequest = mockRequest();
        Calendar.Events.List cancelRequest = mockRequest();
        Calendar.Events.List retryRequest = mockRequest();
        when(events.list("primary")).thenReturn(firstRequest, cancelRequest, retryRequest);
        when(firstRequest.execute()).thenReturn(new Events()
                .setItems(List.of(event("evt-1").setEtag("etag-1"))).setNextSyncToken("sync-token-1"));
        when(cancelRequest.execute()).thenReturn(new Events()
                .setItems(List.of(cancelledEvent("evt-1", "etag-cancelled"))).setNextSyncToken("sync-token-2"));
        // Publish failed - syncToken frozen at sync-token-1, so the retry re-reads the identical
        // cancellation delta: same etag.
        when(retryRequest.execute()).thenReturn(new Events()
                .setItems(List.of(cancelledEvent("evt-1", "etag-cancelled"))).setNextSyncToken("sync-token-2"));

        RealCalendarSourceReader reader = reader();
        reader.read(ReadCursor.full());
        SourceRecord cancelled = reader.read(new ReadCursor(null, "sync-token-1")).records().get(0);
        SourceRecord retry = reader.read(new ReadCursor(null, "sync-token-1")).records().get(0);

        assertThat(cancelled.revision()).isEqualTo(1);
        assertThat(retry.revision()).isEqualTo(1); // NOT 2
        assertThat(retry.correlationId()).isEqualTo(cancelled.correlationId());
        assertThat(retry.changeType()).isEqualTo(com.rnd.bridge.raw.model.ChangeType.DELETE_REVIEW);
    }

    /**
     * First-seen cancelled event: no prior CalendarEventState exists for this eventId at all
     * (see CalendarEventStateReconcilerTest.firstSeenCancelledEventStillGetsANewUuidCorrelationIdAndRevisionZero
     * for the documented policy). The reader must not crash and must still produce a valid UUID
     * correlationId at revision 0, marked DELETE_REVIEW.
     */
    @Test
    void firstSeenCancelledEventGetsAUuidCorrelationIdRevisionZeroAndIsStillDeleteReview() throws IOException {
        Calendar.Events.List request = mockRequest();
        when(events.list("primary")).thenReturn(request);
        when(request.execute()).thenReturn(new Events()
                .setItems(List.of(cancelledEvent("evt-never-seen", "etag-1"))).setNextSyncToken("sync-token-1"));

        SourceRecord record = reader().read(ReadCursor.full()).records().get(0);

        assertThat(java.util.UUID.fromString(record.correlationId())).isNotNull();
        assertThat(record.revision()).isZero();
        assertThat(record.changeType()).isEqualTo(com.rnd.bridge.raw.model.ChangeType.DELETE_REVIEW);
    }

    @Test
    void ordinaryEventCarriesItsEventIdAsSourceRef() throws IOException {
        Calendar.Events.List request = mockRequest();
        when(events.list("primary")).thenReturn(request);
        when(request.execute()).thenReturn(new Events()
                .setItems(List.of(event("evt-1"))).setNextSyncToken("sync-token-1"));

        SourceRecord record = reader().read(ReadCursor.full()).records().get(0);

        assertThat(record.sourceRef()).isEqualTo("evt-1");
    }

    @Test
    void cancelledEventAlsoCarriesItsEventIdAsSourceRef() throws IOException {
        Calendar.Events.List request = mockRequest();
        when(events.list("primary")).thenReturn(request);
        when(request.execute()).thenReturn(new Events()
                .setItems(List.of(cancelledEvent("evt-sparse", "etag-1"))).setNextSyncToken("sync-token-1"));

        SourceRecord record = reader().read(ReadCursor.full()).records().get(0);

        assertThat(record.sourceRef()).isEqualTo("evt-sparse");
    }

    private static GoogleJsonResponseException expiredTokenException() {
        HttpResponseException.Builder builder = new HttpResponseException.Builder(410, "Gone", new HttpHeaders());
        return new GoogleJsonResponseException(builder, null);
    }
}
