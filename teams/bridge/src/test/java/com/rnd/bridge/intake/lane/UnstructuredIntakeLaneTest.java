package com.rnd.bridge.intake.lane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.intake.service.IntakeLandingService;
import com.rnd.bridge.raw.model.RawObjectEvent;
import com.rnd.bridge.raw.model.RawSourceType;
import com.rnd.bridge.raw.model.ScopeDecision;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Covers UnstructuredIntakeLane's own logic: the afterMap override that lands the record. */
class UnstructuredIntakeLaneTest {

    private static final SourceRecord RECORD = new SourceRecord(
            RawSourceType.EMAIL, "kamatech", "id-1", "corr-1", Instant.parse("2026-08-18T09:15:00Z"),
            "hadas@kamatech.org", List.of("hadas@kamatech.org"), "subject", "body",
            "{}".getBytes(StandardCharsets.UTF_8), null, null, 0);

    private static final RawObjectEvent EVENT = RawObjectEvent.create(
            "EMAIL/kamatech/2026-08-18/x.json", RawSourceType.EMAIL, "kamatech", Instant.parse("2026-08-18T09:15:00Z"),
            ScopeDecision.INCLUDED, "sha256:" + "0".repeat(64), null, List.of("hadas@kamatech.org"),
            "application/json", 2L, Instant.parse("2026-08-18T09:15:00Z"), "corr-1", 0);

    @SuppressWarnings("unchecked")
    private final SourceReader<SourceRecord> sourceReader = mock(SourceReader.class);
    @SuppressWarnings("unchecked")
    private final PayloadMapper<SourceRecord, RawObjectEvent> payloadMapper = mock(PayloadMapper.class);
    @SuppressWarnings("unchecked")
    private final PayloadValidator<RawObjectEvent> payloadValidator = mock(PayloadValidator.class);
    @SuppressWarnings("unchecked")
    private final PayloadPublisher<RawObjectEvent> payloadPublisher = mock(PayloadPublisher.class);
    private final IntakeLandingService landingService = mock(IntakeLandingService.class);

    private final UnstructuredIntakeLane lane = new UnstructuredIntakeLane(
            RawSourceType.EMAIL, sourceReader, payloadMapper, payloadValidator, payloadPublisher, landingService);

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
}
