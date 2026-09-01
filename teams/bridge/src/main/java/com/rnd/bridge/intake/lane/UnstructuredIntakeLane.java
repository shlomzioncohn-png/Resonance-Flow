package com.rnd.bridge.intake.lane;

import com.rnd.bridge.core.AbstractIntakeLane;
import com.rnd.bridge.core.PayloadMapper;
import com.rnd.bridge.core.PayloadPublisher;
import com.rnd.bridge.core.PayloadValidator;
import com.rnd.bridge.core.SourceReader;
import com.rnd.bridge.core.error.LaneException;
import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.intake.service.IntakeLandingService;
import com.rnd.bridge.raw.model.RawObjectEvent;
import com.rnd.bridge.raw.model.RawSourceType;
import java.time.Instant;

/**
 * One instance per {@link RawSourceType} this lane serves (EMAIL/SLACK/WHATSAPP/DRIVE/CALENDAR
 * — see {@link UnstructuredIntakeLaneConfig}), each bound to its own {@link SourceReader} but
 * sharing the same mapper/validator/publisher instances. This is a single concrete class
 * parameterized by constructor, not one subclass per source, so it avoids the near-duplicate
 * Lane-subclass boilerplate that was previously the reason to stay with a single hand-rolled
 * orchestrator (see plan.md).
 */
public final class UnstructuredIntakeLane extends AbstractIntakeLane<SourceRecord, RawObjectEvent> {

    private final RawSourceType sourceType;
    private final SourceReader<SourceRecord> sourceReader;
    private final PayloadMapper<SourceRecord, RawObjectEvent> payloadMapper;
    private final PayloadValidator<RawObjectEvent> payloadValidator;
    private final PayloadPublisher<RawObjectEvent> payloadPublisher;
    private final IntakeLandingService landingService;

    UnstructuredIntakeLane(
            RawSourceType sourceType,
            SourceReader<SourceRecord> sourceReader,
            PayloadMapper<SourceRecord, RawObjectEvent> payloadMapper,
            PayloadValidator<RawObjectEvent> payloadValidator,
            PayloadPublisher<RawObjectEvent> payloadPublisher,
            IntakeLandingService landingService) {
        this.sourceType = sourceType;
        this.sourceReader = sourceReader;
        this.payloadMapper = payloadMapper;
        this.payloadValidator = payloadValidator;
        this.payloadPublisher = payloadPublisher;
        this.landingService = landingService;
    }

    public RawSourceType sourceType() {
        return sourceType;
    }

    @Override
    protected SourceReader<SourceRecord> sourceReader() {
        return sourceReader;
    }

    @Override
    protected PayloadMapper<SourceRecord, RawObjectEvent> payloadMapper() {
        return payloadMapper;
    }

    @Override
    protected PayloadValidator<RawObjectEvent> payloadValidator() {
        return payloadValidator;
    }

    @Override
    protected PayloadPublisher<RawObjectEvent> payloadPublisher() {
        return payloadPublisher;
    }

    /** Lands the record's raw bytes in MinIO - the one side effect this lane needs beyond publishing. */
    @Override
    protected void afterMap(SourceRecord source, RawObjectEvent payload, Instant asOf) {
        try {
            landingService.land(payload.objectKey(), source, payload.scopeDecision(), asOf);
        } catch (RuntimeException e) {
            throw new LaneException(
                    "Failed to land sourceType=" + source.sourceType() + " naturalId=" + source.naturalId(), e);
        }
    }
}
