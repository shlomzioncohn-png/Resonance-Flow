package com.rnd.bridge.intake.lane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.rnd.bridge.core.IntakeResult;
import com.rnd.bridge.core.PayloadMapper;
import com.rnd.bridge.core.PayloadPublisher;
import com.rnd.bridge.core.PayloadValidator;
import com.rnd.bridge.core.ReadCursor;
import com.rnd.bridge.core.SourceReader;
import com.rnd.bridge.core.error.LaneException;
import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.intake.service.IntakeLandingService;
import com.rnd.bridge.raw.model.ChangeType;
import com.rnd.bridge.raw.model.RawObjectEvent;
import com.rnd.bridge.raw.model.RawSourceType;
import com.rnd.bridge.raw.model.ScopeDecision;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Covers UnstructuredIntakeLane's own logic: the afterMap override that lands the record, and
 * the afterPublish override that confirms a chat group's publish state. */
class UnstructuredIntakeLaneTest {

    private static final SourceRecord RECORD = new SourceRecord(
            RawSourceType.EMAIL, "kamatech", "id-1", "corr-1", Instant.parse("2026-08-18T09:15:00Z"),
            "hadas@kamatech.org", List.of("hadas@kamatech.org"), "subject", "body",
            "{}".getBytes(StandardCharsets.UTF_8), null, null, 0, 0, List.of(), 0);

    private static final RawObjectEvent EVENT = RawObjectEvent.create(
            "EMAIL/kamatech/2026-08-18/x.json", RawSourceType.EMAIL, "kamatech", Instant.parse("2026-08-18T09:15:00Z"),
            ScopeDecision.INCLUDED, "sha256:" + "0".repeat(64), null, List.of("hadas@kamatech.org"),
            "application/json", 2L, Instant.parse("2026-08-18T09:15:00Z"), "corr-1", 0, 0, 0, ChangeType.UPSERT);

    // A chat transcript record (attachmentIndex 0, non-null groupId) - the shape that actually
    // reaches the GroupStateStore confirmation branch inside afterPublish, unlike RECORD above.
    private static final SourceRecord SLACK_RECORD = new SourceRecord(
            RawSourceType.SLACK, "kamatech", "chat-a|2026-08-18T09:00:00Z", "corr-2",
            Instant.parse("2026-08-18T09:00:00Z"), "ruth", List.of("ruth"), null, "ruth: hi",
            "ruth: hi".getBytes(StandardCharsets.UTF_8), null, null, 0, 0, List.of("m1"), 0, "g1");

    private static final RawObjectEvent SLACK_EVENT = RawObjectEvent.create(
            "SLACK/kamatech/2026-08-18/x.json", RawSourceType.SLACK, "kamatech", Instant.parse("2026-08-18T09:00:00Z"),
            ScopeDecision.INCLUDED, "sha256:" + "1".repeat(64), null, List.of("ruth"), "text/plain", 8L,
            Instant.parse("2026-08-18T09:00:00Z"), "corr-2", 0, 0, 0, ChangeType.UPSERT);

    // A Calendar record at revision 1 (naturalId is event.getId() - see
    // RealCalendarSourceReader.toSourceRecord) - the shape that reaches
    // CalendarEventStateStore.confirmPublished inside afterPublish.
    private static final SourceRecord CALENDAR_RECORD = new SourceRecord(
            RawSourceType.CALENDAR, "kamatech", "evt-1", "corr-3", Instant.parse("2026-08-19T13:00:00Z"),
            "hadas@kamatech.org", List.of("hadas@kamatech.org"), "meeting", "body",
            "{}".getBytes(StandardCharsets.UTF_8), null, null, 0, 0, List.of(), 1);

    private static final RawObjectEvent CALENDAR_EVENT = RawObjectEvent.create(
            "CALENDAR/kamatech/2026-08-19/x.json", RawSourceType.CALENDAR, "kamatech",
            Instant.parse("2026-08-19T13:00:00Z"), ScopeDecision.INCLUDED, "sha256:" + "2".repeat(64), null,
            List.of("hadas@kamatech.org"), "application/json", 2L, Instant.parse("2026-08-19T13:00:00Z"), "corr-3",
            0, 0, 1, ChangeType.UPSERT);

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
    private final CalendarEventStateStore calendarEventStateStore = mock(CalendarEventStateStore.class);

    private final UnstructuredIntakeLane lane = new UnstructuredIntakeLane(
            RawSourceType.EMAIL, sourceReader, payloadMapper, payloadValidator, payloadPublisher, landingService,
            groupStateStore, calendarEventStateStore);

    // afterPublish keys strictly off source.sourceType()/tenantId(), not the lane's own
    // declared sourceType - but a dedicated SLACK-typed lane keeps these tests' intent explicit.
    private final UnstructuredIntakeLane slackLane = new UnstructuredIntakeLane(
            RawSourceType.SLACK, sourceReader, payloadMapper, payloadValidator, payloadPublisher, landingService,
            groupStateStore, calendarEventStateStore);

    private final UnstructuredIntakeLane calendarLane = new UnstructuredIntakeLane(
            RawSourceType.CALENDAR, sourceReader, payloadMapper, payloadValidator, payloadPublisher, landingService,
            groupStateStore, calendarEventStateStore);

    @Test
    void afterMapLandsUsingTheMappedPayloadsObjectKeyAndScopeDecision() {
        when(sourceReader.read(any())).thenReturn(Stream.of(RECORD));
        when(payloadMapper.map(any(), any())).thenReturn(EVENT);
        Instant asOf = Instant.parse("2026-08-31T10:00:00Z");

        IntakeResult result = lane.run(ReadCursor.full(), asOf);

        verify(landingService).land(EVENT.objectKey(), RECORD, EVENT.scopeDecision(), asOf);
        verify(payloadValidator).validate(EVENT);
        verify(payloadPublisher).publish(EVENT);
        assertThat(result).isEqualTo(new IntakeResult(1, 1, 0, List.of()));
        // EMAIL records carry no sourceMessageIds - afterPublish's chat-group confirmation
        // gate must never fire for a source that was never reconciled into a GroupState.
        verifyNoInteractions(groupStateStore);
    }

    @Test
    void landingFailureIsRecordedAsRejectedAndSkipsValidateAndPublish() {
        when(sourceReader.read(any())).thenReturn(Stream.of(RECORD));
        when(payloadMapper.map(any(), any())).thenReturn(EVENT);
        doThrow(new IllegalStateException("MinIO unreachable")).when(landingService)
                .land(any(), any(), any(), any());

        IntakeResult result = lane.run(ReadCursor.full(), Instant.now());

        assertThat(result.read()).isEqualTo(1);
        assertThat(result.published()).isEqualTo(0);
        assertThat(result.rejected()).isEqualTo(1);
        assertThat(result.errors()).hasSize(1);
        verify(payloadValidator, never()).validate(any());
        verify(payloadPublisher, never()).publish(any());
    }

    @Test
    void chatGroupConfirmationSucceedingCountsTheRecordAsPublishedNotRejected() {
        when(sourceReader.read(any())).thenReturn(Stream.of(SLACK_RECORD));
        when(payloadMapper.map(any(), any())).thenReturn(SLACK_EVENT);

        IntakeResult result = slackLane.run(ReadCursor.full(), Instant.now());

        // "g1" comes straight from SLACK_RECORD.groupId() now - no findGroupIdForMessage stub
        // needed, since afterPublish no longer reverse-resolves it through the messageId index.
        verify(groupStateStore).confirmPublished(RawSourceType.SLACK, "kamatech", "g1", 0);
        assertThat(result).isEqualTo(new IntakeResult(1, 1, 0, List.of()));
    }

    /**
     * The regression this guards: afterPublish used to run AFTER published++ already counted
     * the record, so a failure here got the record counted as both published (already
     * incremented) and rejected (the catch block below) at once. run() now only increments
     * published once afterPublish returns successfully, and UnstructuredIntakeLane.afterPublish
     * wraps GroupStateStore's own RuntimeExceptions (IllegalStateException/UncheckedIOException -
     * never LaneException) in LaneException, so run()'s catch(LaneException) actually catches
     * this instead of letting it escape uncaught and abort the whole batch.
     */
    @Test
    void chatGroupConfirmationFailureAfterKafkaPublishIsRejectedNotPublished() {
        when(sourceReader.read(any())).thenReturn(Stream.of(SLACK_RECORD));
        when(payloadMapper.map(any(), any())).thenReturn(SLACK_EVENT);
        doThrow(new IllegalStateException("MinIO unreachable"))
                .when(groupStateStore).confirmPublished(RawSourceType.SLACK, "kamatech", "g1", 0);

        // If the failure escaped run() uncaught, this call itself would throw instead of
        // returning a result - reaching the assertions below is already proof it didn't.
        IntakeResult result = slackLane.run(ReadCursor.full(), Instant.now());

        verify(payloadPublisher).publish(SLACK_EVENT); // Kafka publish itself genuinely succeeded
        assertThat(result.read()).isEqualTo(1);
        assertThat(result.published()).isZero(); // not double-counted despite the Kafka success
        assertThat(result.rejected()).isEqualTo(1);
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0)).isInstanceOf(LaneException.class);
    }

    @Test
    void calendarEventConfirmationSucceedingCountsTheRecordAsPublishedNotRejected() {
        when(sourceReader.read(any())).thenReturn(Stream.of(CALENDAR_RECORD));
        when(payloadMapper.map(any(), any())).thenReturn(CALENDAR_EVENT);

        IntakeResult result = calendarLane.run(ReadCursor.full(), Instant.now());

        verify(calendarEventStateStore).confirmPublished("kamatech", "evt-1", 1);
        assertThat(result).isEqualTo(new IntakeResult(1, 1, 0, List.of()));
    }

    /** Mirrors chatGroupConfirmationFailureAfterKafkaPublishIsRejectedNotPublished, for Calendar's
     * own confirmation path instead of the chat-group one. */
    @Test
    void calendarEventConfirmationFailureAfterKafkaPublishIsRejectedNotPublished() {
        when(sourceReader.read(any())).thenReturn(Stream.of(CALENDAR_RECORD));
        when(payloadMapper.map(any(), any())).thenReturn(CALENDAR_EVENT);
        doThrow(new IllegalStateException("MinIO unreachable"))
                .when(calendarEventStateStore).confirmPublished("kamatech", "evt-1", 1);

        IntakeResult result = calendarLane.run(ReadCursor.full(), Instant.now());

        verify(payloadPublisher).publish(CALENDAR_EVENT); // Kafka publish itself genuinely succeeded
        assertThat(result.published()).isZero();
        assertThat(result.rejected()).isEqualTo(1);
        assertThat(result.errors().get(0)).isInstanceOf(LaneException.class);
    }
}
