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
    private final GroupStateStore groupStateStore;

    UnstructuredIntakeLane(
            RawSourceType sourceType,
            SourceReader<SourceRecord> sourceReader,
            PayloadMapper<SourceRecord, RawObjectEvent> payloadMapper,
            PayloadValidator<RawObjectEvent> payloadValidator,
            PayloadPublisher<RawObjectEvent> payloadPublisher,
            IntakeLandingService landingService,
            GroupStateStore groupStateStore) {
        this.sourceType = sourceType;
        this.sourceReader = sourceReader;
        this.payloadMapper = payloadMapper;
        this.payloadValidator = payloadValidator;
        this.payloadPublisher = payloadPublisher;
        this.landingService = landingService;
        this.groupStateStore = groupStateStore;
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

    /**
     * Confirms a chat group's publish state once its transcript record has actually been
     * published - see ChatGroupStateReconciler.mergeAndMaybePublish for why a group is only
     * ever persisted as pending until this fires. Gated on attachmentIndex()==0 (the
     * transcript/body record, never an attachment) and a non-empty sourceMessageIds (only
     * chat records built from a persisted group carry one - see SourceRecord's javadoc), so
     * this is a no-op for every other source and for attachment records.
     * source.correlationId() is Mind's UUID, not GroupStateStore's own storage key (see
     * GroupState's javadoc) - so the group is instead resolved back to its internal groupId via
     * the messageId index, keyed on any one of this record's sourceMessageIds (every message in
     * a persisted group is indexed to it, including ones landed in earlier runs). ifPresent
     * rather than orElseThrow: same not-found tolerance GroupStateStore.confirmPublished itself
     * already has for a group that genuinely isn't there.
     * Passes source.revision() through so GroupStateStore.confirmPublished only flips the
     * group published if that's still its current revision - this ack belongs to the specific
     * revision this SourceRecord was built from, which may no longer be current by the time
     * the ack comes back (see GroupStateStore.confirmPublished's javadoc).
     * GroupStateStore's own failures surface as plain RuntimeExceptions (UncheckedIOException,
     * IllegalStateException - see its and ControlPlaneStateStore's javadoc), not LaneException,
     * so they're wrapped here the same way afterMap wraps landingService failures: run() only
     * catches LaneException, and only counts this source as published once afterPublish returns
     * successfully (see AbstractIntakeLane.run()/afterPublish's javadoc) - without this wrapping,
     * a transient MinIO failure here would escape run() uncaught and abort the whole batch
     * instead of being recorded as a single rejected record and retried next run (Slack's cursor
     * freeze-on-reject already makes that retry both safe and necessary - see
     * UnstructuredIntakeController).
     */
    @Override
    protected void afterPublish(SourceRecord source, RawObjectEvent payload, Instant asOf) {
        try {
            if (source.attachmentIndex() == 0 && !source.sourceMessageIds().isEmpty()) {
                groupStateStore
                        .findGroupIdForMessage(source.sourceType(), source.tenantId(), source.sourceMessageIds().get(0))
                        .ifPresent(groupId -> groupStateStore.confirmPublished(
                                source.sourceType(), source.tenantId(), groupId, source.revision()));
            }
        } catch (RuntimeException e) {
            throw new LaneException(
                    "Failed to confirm chat group publish for sourceType=" + source.sourceType()
                            + " naturalId=" + source.naturalId(), e);
        }
    }
}
