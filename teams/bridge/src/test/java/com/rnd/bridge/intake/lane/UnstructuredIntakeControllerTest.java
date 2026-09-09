package com.rnd.bridge.intake.lane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.rnd.bridge.core.IntakeResult;
import com.rnd.bridge.core.PayloadMapper;
import com.rnd.bridge.core.PayloadPublisher;
import com.rnd.bridge.core.PayloadValidator;
import com.rnd.bridge.core.ReadCursor;
import com.rnd.bridge.core.SourceReader;
import com.rnd.bridge.core.error.LaneException;
import com.rnd.bridge.intake.controller.UnstructuredIntakeController;
import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.intake.service.IntakeLandingService;
import com.rnd.bridge.raw.model.RawObjectEvent;
import com.rnd.bridge.raw.model.RawSourceType;
import com.rnd.bridge.raw.model.ScopeDecision;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

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
            "{}".getBytes(StandardCharsets.UTF_8), null, null, 0, List.of(), 0);

    private static final RawObjectEvent EVENT = RawObjectEvent.create(
            "EMAIL/kamatech/2026-08-18/x.json", RawSourceType.EMAIL, "kamatech", Instant.parse("2026-08-18T09:15:00Z"),
            ScopeDecision.INCLUDED, "sha256:" + "0".repeat(64), null, List.of("hadas@kamatech.org"),
            "application/json", 2L, Instant.parse("2026-08-18T09:15:00Z"), "corr-1", 0);

    // A chat transcript record (attachmentIndex 0, non-empty sourceMessageIds) - the shape that
    // actually reaches UnstructuredIntakeLane.afterPublish's GroupStateStore confirmation branch.
    private static final SourceRecord SLACK_RECORD = new SourceRecord(
            RawSourceType.SLACK, "kamatech", "chat-a|2026-08-18T09:00:00Z", "corr-2",
            Instant.parse("2026-08-18T09:00:00Z"), "ruth", List.of("ruth"), null, "ruth: hi",
            "ruth: hi".getBytes(StandardCharsets.UTF_8), null, null, 0, List.of("m1"), 0);

    private static final RawObjectEvent SLACK_EVENT = RawObjectEvent.create(
            "SLACK/kamatech/2026-08-18/x.json", RawSourceType.SLACK, "kamatech", Instant.parse("2026-08-18T09:00:00Z"),
            ScopeDecision.INCLUDED, "sha256:" + "1".repeat(64), null, List.of("ruth"), "text/plain", 8L,
            Instant.parse("2026-08-18T09:00:00Z"), "corr-2", 0);

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

    private UnstructuredIntakeLane laneFor(RawSourceType sourceType) {
        return new UnstructuredIntakeLane(
                sourceType, sourceReader, payloadMapper, payloadValidator, payloadPublisher, landingService,
                groupStateStore);
    }

    @Test
    void slackCursorIsNotAdvancedWhenTheRunHasRejectedRecords() {
        UnstructuredIntakeController controller =
                new UnstructuredIntakeController(List.of(laneFor(RawSourceType.SLACK)), cursorStore);
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
        UnstructuredIntakeController controller =
                new UnstructuredIntakeController(List.of(laneFor(RawSourceType.SLACK)), cursorStore);
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
        UnstructuredIntakeController controller =
                new UnstructuredIntakeController(List.of(laneFor(RawSourceType.SLACK)), cursorStore);
        when(cursorStore.load(RawSourceType.SLACK, "kamatech")).thenReturn(ReadCursor.full());
        when(sourceReader.read(any())).thenReturn(Stream.of(SLACK_RECORD));
        when(payloadMapper.map(any(), any())).thenReturn(SLACK_EVENT);
        when(groupStateStore.findGroupIdForMessage(RawSourceType.SLACK, "kamatech", "m1"))
                .thenReturn(Optional.of("g1"));
        doThrow(new IllegalStateException("MinIO unreachable"))
                .when(groupStateStore).confirmPublished(RawSourceType.SLACK, "kamatech", "g1", 0);

        IntakeResult result = controller.run(RawSourceType.SLACK);

        assertThat(result.published()).isZero();
        assertThat(result.rejected()).isEqualTo(1);
        verify(cursorStore, never()).save(any(), any(), any());
    }

    @Test
    void nonSlackCursorAdvancesEvenWhenTheRunHasRejectedRecords() {
        UnstructuredIntakeController controller =
                new UnstructuredIntakeController(List.of(laneFor(RawSourceType.EMAIL)), cursorStore);
        when(cursorStore.load(RawSourceType.EMAIL, "kamatech")).thenReturn(ReadCursor.full());
        when(sourceReader.read(any())).thenReturn(Stream.of(RECORD));
        when(payloadMapper.map(any(), any())).thenReturn(EVENT);
        doThrow(new LaneException("kafka down")).when(payloadPublisher).publish(any());

        IntakeResult result = controller.run(RawSourceType.EMAIL);

        assertThat(result.rejected()).isEqualTo(1);
        verify(cursorStore).save(eq(RawSourceType.EMAIL), eq("kamatech"), any());
    }
}
