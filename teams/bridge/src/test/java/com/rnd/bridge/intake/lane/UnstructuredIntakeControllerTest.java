package com.rnd.bridge.intake.lane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rnd.bridge.core.IntakeResult;
import com.rnd.bridge.core.KeyedLock;
import com.rnd.bridge.core.PayloadMapper;
import com.rnd.bridge.core.PayloadPublisher;
import com.rnd.bridge.core.PayloadValidator;
import com.rnd.bridge.core.ReadCursor;
import com.rnd.bridge.core.SourceReader;
import com.rnd.bridge.core.error.LaneException;
import com.rnd.bridge.intake.controller.UnstructuredIntakeController;
import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.intake.parser.calendar.CalendarReadResult;
import com.rnd.bridge.intake.parser.calendar.CalendarSourceReader;
import com.rnd.bridge.intake.service.IntakeLandingService;
import com.rnd.bridge.raw.model.ChangeType;
import com.rnd.bridge.raw.model.RawObjectEvent;
import com.rnd.bridge.raw.model.RawSourceType;
import com.rnd.bridge.raw.model.ScopeDecision;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Covers the Slack-only cursor freeze: a rejected record within a Slack run must not advance
 * the cursor (so the same window is retried next run - see ChatGroupStateReconciler's two-phase
 * publish state, which makes that replay both safe and necessary), while every other source
 * keeps advancing unconditionally, exactly as before.
 * Lives in this package (rather than mirroring UnstructuredIntakeController's own
 * intake.controller package) because it needs to construct real UnstructuredIntakeLane
 * instances, whose constructor is intentionally package-private to intake.lane.
 */
class UnstructuredIntakeControllerTest {

    private static final SourceRecord RECORD = new SourceRecord(
            RawSourceType.EMAIL, "kamatech", "id-1", "corr-1", Instant.parse("2026-08-18T09:15:00Z"),
            "hadas@kamatech.org", List.of("hadas@kamatech.org"), "subject", "body",
            "{}".getBytes(StandardCharsets.UTF_8), null, null, 0, 0, List.of(), 0);

    private static final RawObjectEvent EVENT = RawObjectEvent.create(
            "EMAIL/kamatech/2026-08-18/x.json", RawSourceType.EMAIL, "kamatech", Instant.parse("2026-08-18T09:15:00Z"),
            ScopeDecision.INCLUDED, "sha256:" + "0".repeat(64), null, List.of("hadas@kamatech.org"),
            "application/json", 2L, Instant.parse("2026-08-18T09:15:00Z"), "corr-1", 0, 0, 0, ChangeType.UPSERT);

    // A chat transcript record (attachmentIndex 0, non-null groupId) - the shape that actually
    // reaches UnstructuredIntakeLane.afterPublish's GroupStateStore confirmation branch.
    private static final SourceRecord SLACK_RECORD = new SourceRecord(
            RawSourceType.SLACK, "kamatech", "chat-a|2026-08-18T09:00:00Z", "corr-2",
            Instant.parse("2026-08-18T09:00:00Z"), "ruth", List.of("ruth"), null, "ruth: hi",
            "ruth: hi".getBytes(StandardCharsets.UTF_8), null, null, 0, 0, List.of("m1"), 0, "g1");

    private static final RawObjectEvent SLACK_EVENT = RawObjectEvent.create(
            "SLACK/kamatech/2026-08-18/x.json", RawSourceType.SLACK, "kamatech", Instant.parse("2026-08-18T09:00:00Z"),
            ScopeDecision.INCLUDED, "sha256:" + "1".repeat(64), null, List.of("ruth"), "text/plain", 8L,
            Instant.parse("2026-08-18T09:00:00Z"), "corr-2", 0, 0, 0, ChangeType.UPSERT);

    private static final SourceRecord CALENDAR_RECORD = new SourceRecord(
            RawSourceType.CALENDAR, "kamatech", "event-1", "corr-3", Instant.parse("2026-08-18T09:15:00Z"),
            "malki@kamatech.org", List.of("malki@kamatech.org"), "meeting", "body",
            "{}".getBytes(StandardCharsets.UTF_8), null, null, 0, 0, List.of(), 0);

    private static final RawObjectEvent CALENDAR_EVENT = RawObjectEvent.create(
            "CALENDAR/kamatech/2026-08-18/x.json", RawSourceType.CALENDAR, "kamatech",
            Instant.parse("2026-08-18T09:15:00Z"), ScopeDecision.INCLUDED, "sha256:" + "2".repeat(64), null,
            List.of("malki@kamatech.org"), "application/json", 2L, Instant.parse("2026-08-18T09:15:00Z"), "corr-3",
            0, 0, 0, ChangeType.UPSERT);

    @SuppressWarnings("unchecked")
    private final SourceReader<SourceRecord> sourceReader = mock(SourceReader.class);
    @SuppressWarnings("unchecked")
    private final PayloadMapper<SourceRecord, RawObjectEvent> payloadMapper = mock(PayloadMapper.class);
    @SuppressWarnings("unchecked")
    private final PayloadValidator<RawObjectEvent> payloadValidator = mock(PayloadValidator.class);
    @SuppressWarnings("unchecked")
    private final PayloadPublisher<RawObjectEvent> payloadPublisher = mock(PayloadPublisher.class);
    private final IntakeLandingService landingService = mock(IntakeLandingService.class);
    private final GroupStateStore groupStateStore = mock(GroupStateStore.class);
    private final CursorStore cursorStore = mock(CursorStore.class);
    private final CalendarSourceReader calendarSourceReader = mock(CalendarSourceReader.class);
    private final CalendarEventStateStore calendarEventStateStore = mock(CalendarEventStateStore.class);
    private final KeyedLock calendarCursorLock = new KeyedLock();

    private UnstructuredIntakeLane laneFor(RawSourceType sourceType) {
        return new UnstructuredIntakeLane(
                sourceType, sourceReader, payloadMapper, payloadValidator, payloadPublisher, landingService,
                groupStateStore, calendarEventStateStore);
    }

    private UnstructuredIntakeController controllerFor(RawSourceType sourceType) {
        return new UnstructuredIntakeController(
                List.of(laneFor(sourceType)), cursorStore, calendarSourceReader, calendarCursorLock);
    }

    @Test
    void slackCursorIsNotAdvancedWhenTheRunHasRejectedRecords() {
        UnstructuredIntakeController controller = controllerFor(RawSourceType.SLACK);
        when(cursorStore.load(RawSourceType.SLACK, "kamatech")).thenReturn(ReadCursor.full());
        when(sourceReader.read(any())).thenReturn(Stream.of(RECORD));
        when(payloadMapper.map(any(), any())).thenReturn(EVENT);
        doThrow(new LaneException("kafka down")).when(payloadPublisher).publish(any());

        IntakeResult result = controller.run(RawSourceType.SLACK);

        assertThat(result.rejected()).isEqualTo(1);
        verify(cursorStore, never()).save(any(), any(), any());
    }

    @Test
    void slackCursorAdvancesWhenTheRunFullySucceeds() {
        UnstructuredIntakeController controller = controllerFor(RawSourceType.SLACK);
        when(cursorStore.load(RawSourceType.SLACK, "kamatech")).thenReturn(ReadCursor.full());
        when(sourceReader.read(any())).thenReturn(Stream.of(RECORD));
        when(payloadMapper.map(any(), any())).thenReturn(EVENT);

        IntakeResult result = controller.run(RawSourceType.SLACK);

        assertThat(result.rejected()).isEqualTo(0);
        verify(cursorStore).save(eq(RawSourceType.SLACK), eq("kamatech"), any());
    }

    /**
     * End-to-end version of AbstractIntakeLane's afterPublish-failure fix: a chat group
     * confirmation failure after Kafka already accepted the transcript event must not be
     * mistaken for a successful record, and must leave the Slack cursor frozen exactly like
     * any other rejection - so this window is retried next run instead of silently appearing
     * to have succeeded (see UnstructuredIntakeLaneTest for the lane-level version of this).
     */
    @Test
    void slackCursorIsNotAdvancedWhenChatGroupConfirmationFailsAfterKafkaPublishSucceeds() {
        UnstructuredIntakeController controller = controllerFor(RawSourceType.SLACK);
        when(cursorStore.load(RawSourceType.SLACK, "kamatech")).thenReturn(ReadCursor.full());
        when(sourceReader.read(any())).thenReturn(Stream.of(SLACK_RECORD));
        when(payloadMapper.map(any(), any())).thenReturn(SLACK_EVENT);
        doThrow(new IllegalStateException("MinIO unreachable"))
                .when(groupStateStore).confirmPublished(RawSourceType.SLACK, "kamatech", "g1", 0);

        IntakeResult result = controller.run(RawSourceType.SLACK);

        assertThat(result.published()).isZero();
        assertThat(result.rejected()).isEqualTo(1);
        verify(cursorStore, never()).save(any(), any(), any());
    }

    @Test
    void nonSlackCursorAdvancesEvenWhenTheRunHasRejectedRecords() {
        UnstructuredIntakeController controller = controllerFor(RawSourceType.EMAIL);
        when(cursorStore.load(RawSourceType.EMAIL, "kamatech")).thenReturn(ReadCursor.full());
        when(sourceReader.read(any())).thenReturn(Stream.of(RECORD));
        when(payloadMapper.map(any(), any())).thenReturn(EVENT);
        doThrow(new LaneException("kafka down")).when(payloadPublisher).publish(any());

        IntakeResult result = controller.run(RawSourceType.EMAIL);

        assertThat(result.rejected()).isEqualTo(1);
        verify(cursorStore).save(eq(RawSourceType.EMAIL), eq("kamatech"), any());
    }

    /**
     * Full/initial sync: no stored syncToken, the read completes fully, and the reader reports
     * the nextSyncToken it obtained from the final page - the controller must persist exactly
     * that token as the new cursor once the whole run has succeeded.
     */
    @Test
    void calendarCursorPersistsTheNewSyncTokenWhenTheRunFullySucceeds() {
        UnstructuredIntakeController controller = controllerFor(RawSourceType.CALENDAR);
        when(cursorStore.load(RawSourceType.CALENDAR, "kamatech")).thenReturn(ReadCursor.full());
        when(calendarSourceReader.read(any()))
                .thenReturn(new CalendarReadResult(List.of(CALENDAR_RECORD), "sync-token-2"));
        when(payloadMapper.map(any(), any())).thenReturn(CALENDAR_EVENT);

        IntakeResult result = controller.run(RawSourceType.CALENDAR);

        assertThat(result.rejected()).isEqualTo(0);
        ArgumentCaptor<ReadCursor> captor = ArgumentCaptor.forClass(ReadCursor.class);
        verify(cursorStore).save(eq(RawSourceType.CALENDAR), eq("kamatech"), captor.capture());
        assertThat(captor.getValue().syncToken()).isEqualTo("sync-token-2");
    }

    /**
     * Safe token advancement: if any record from the batch Google returned under the current
     * syncToken failed to publish, the controller must NOT persist the new syncToken the reader
     * obtained - doing so would permanently lose that record, since Google would never resend it
     * under a later incremental sync. The old stored token must stay in place so next run retries
     * the same window.
     */
    @Test
    void calendarCursorIsNotAdvancedWhenTheRunHasRejectedRecords() {
        UnstructuredIntakeController controller = controllerFor(RawSourceType.CALENDAR);
        when(cursorStore.load(RawSourceType.CALENDAR, "kamatech")).thenReturn(new ReadCursor(null, "sync-token-1"));
        when(calendarSourceReader.read(any()))
                .thenReturn(new CalendarReadResult(List.of(CALENDAR_RECORD), "sync-token-2"));
        when(payloadMapper.map(any(), any())).thenReturn(CALENDAR_EVENT);
        doThrow(new LaneException("kafka down")).when(payloadPublisher).publish(any());

        IntakeResult result = controller.run(RawSourceType.CALENDAR);

        assertThat(result.rejected()).isEqualTo(1);
        verify(cursorStore, never()).save(any(), any(), any());
    }

    /**
     * A read that produced no new token to advance to (e.g. the reader never obtained one) must
     * not overwrite whatever cursor is already stored.
     */
    @Test
    void calendarCursorIsUntouchedWhenTheReaderReportsNoNewSyncToken() {
        UnstructuredIntakeController controller = controllerFor(RawSourceType.CALENDAR);
        when(cursorStore.load(RawSourceType.CALENDAR, "kamatech")).thenReturn(ReadCursor.full());
        when(calendarSourceReader.read(any())).thenReturn(new CalendarReadResult(List.of(CALENDAR_RECORD), null));
        when(payloadMapper.map(any(), any())).thenReturn(CALENDAR_EVENT);

        IntakeResult result = controller.run(RawSourceType.CALENDAR);

        assertThat(result.rejected()).isEqualTo(0);
        verify(cursorStore, never()).save(any(), any(), any());
    }

    /**
     * The core correctness property from the Calendar cursor concurrency fix: while run A still
     * holds the lock (blocked inside calendarSourceReader.read, before it has saved anything),
     * a concurrently-started run B must not be able to load the stored cursor - it has to wait
     * for A to finish (including A's save) before B's own load() runs. This is what prevents the
     * stale-overwrite race (B loading the old token, then B's later save clobbering A's newer
     * one) that motivated wrapping the whole load-read-process-save sequence in one lock instead
     * of locking load()/save() individually or save() alone.
     */
    @Test
    void secondCalendarRunCannotLoadTheCursorWhileTheFirstRunStillHoldsTheLock() throws Exception {
        UnstructuredIntakeController controller = controllerFor(RawSourceType.CALENDAR);
        AtomicReference<String> storedToken = new AtomicReference<>(null);
        when(cursorStore.load(RawSourceType.CALENDAR, "kamatech"))
                .thenAnswer(inv -> new ReadCursor(null, storedToken.get()));
        doAnswer(inv -> {
            storedToken.set(((ReadCursor) inv.getArgument(2)).syncToken());
            return null;
        }).when(cursorStore).save(eq(RawSourceType.CALENDAR), eq("kamatech"), any());

        CountDownLatch runAEnteredRead = new CountDownLatch(1);
        CountDownLatch releaseRunA = new CountDownLatch(1);
        List<String> tokensSeenByRead = new CopyOnWriteArrayList<>();
        when(calendarSourceReader.read(any())).thenAnswer(inv -> {
            ReadCursor cursor = inv.getArgument(0);
            tokensSeenByRead.add(cursor.syncToken());
            if (tokensSeenByRead.size() == 1) {
                runAEnteredRead.countDown();
                assertThat(releaseRunA.await(5, TimeUnit.SECONDS)).isTrue();
                return new CalendarReadResult(List.of(CALENDAR_RECORD), "TA");
            }
            return new CalendarReadResult(List.of(CALENDAR_RECORD), "TB");
        });
        when(payloadMapper.map(any(), any())).thenReturn(CALENDAR_EVENT);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<IntakeResult> runA = executor.submit(() -> controller.run(RawSourceType.CALENDAR));
            assertThat(runAEnteredRead.await(5, TimeUnit.SECONDS)).isTrue();

            Future<IntakeResult> runB = executor.submit(() -> controller.run(RawSourceType.CALENDAR));
            // Run A is still blocked inside read(), holding the lock before its own save() - give
            // run B every chance to (incorrectly) race ahead and load the stale T0 cursor.
            Thread.sleep(200);
            assertThat(tokensSeenByRead).hasSize(1);

            releaseRunA.countDown();
            runA.get(5, TimeUnit.SECONDS);
            runB.get(5, TimeUnit.SECONDS);

            assertThat(tokensSeenByRead).containsExactly(null, "TA");
            assertThat(storedToken.get()).isEqualTo("TB");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void calendarCursorLockIsReleasedAfterAReadFailureSoALaterRunIsNotBlocked() {
        UnstructuredIntakeController controller = controllerFor(RawSourceType.CALENDAR);
        when(cursorStore.load(RawSourceType.CALENDAR, "kamatech")).thenReturn(ReadCursor.full());
        AtomicInteger callCount = new AtomicInteger();
        when(calendarSourceReader.read(any())).thenAnswer(inv -> {
            if (callCount.getAndIncrement() == 0) {
                throw new IllegalStateException("Google Calendar read failed");
            }
            return new CalendarReadResult(List.of(CALENDAR_RECORD), "sync-token-after-failure");
        });
        when(payloadMapper.map(any(), any())).thenReturn(CALENDAR_EVENT);

        assertThatThrownBy(() -> controller.run(RawSourceType.CALENDAR)).isInstanceOf(IllegalStateException.class);
        verify(cursorStore, never()).save(any(), any(), any());

        IntakeResult result = controller.run(RawSourceType.CALENDAR);

        assertThat(result.rejected()).isEqualTo(0);
        verify(cursorStore).save(eq(RawSourceType.CALENDAR), eq("kamatech"), any());
    }

    @Test
    void calendarCursorLockIsReleasedWhenRecordsAreRejectedSoALaterRunIsNotBlocked() {
        UnstructuredIntakeController controller = controllerFor(RawSourceType.CALENDAR);
        when(cursorStore.load(RawSourceType.CALENDAR, "kamatech")).thenReturn(ReadCursor.full());
        when(calendarSourceReader.read(any()))
                .thenReturn(new CalendarReadResult(List.of(CALENDAR_RECORD), "sync-token-2"));
        when(payloadMapper.map(any(), any())).thenReturn(CALENDAR_EVENT);
        doThrow(new LaneException("kafka down")).when(payloadPublisher).publish(any());

        IntakeResult firstResult = controller.run(RawSourceType.CALENDAR);
        assertThat(firstResult.rejected()).isEqualTo(1);
        verify(cursorStore, never()).save(any(), any(), any());

        // Second run must not be blocked by the first run's lock, which the rejection must have
        // released just like a successful run would.
        doNothing().when(payloadPublisher).publish(any());
        IntakeResult secondResult = controller.run(RawSourceType.CALENDAR);

        assertThat(secondResult.rejected()).isEqualTo(0);
        verify(cursorStore).save(eq(RawSourceType.CALENDAR), eq("kamatech"), any());
    }
}
