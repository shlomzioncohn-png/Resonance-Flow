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
import com.rnd.bridge.raw.model.RawObjectRef;
import com.rnd.bridge.raw.model.RawSourceType;
import java.time.Instant;
import java.util.List;

/**
 * One instance per {@link RawSourceType} this lane serves (EMAIL/SLACK/WHATSAPP/DRIVE/CALENDAR
 * — see {@link UnstructuredIntakeLaneConfig}), each bound to its own {@link SourceReader} but
 * sharing the same mapper/validator/publisher instances. This is a single concrete class
 * parameterized by constructor, not one subclass per source, so it avoids the near-duplicate
 * Lane-subclass boilerplate that was previously the reason to stay with a single hand-rolled
 * orchestrator (see plan.md).
 * <p>
 * The unit of work is one correlationId-group (a logical item's body/transcript plus every
 * attachment - see SourceRecordGrouper), not one SourceRecord: every object in the group lands
 * to MinIO (afterMap) before the single RawObjectEvent for the whole group is validated and
 * published, and confirmation (afterPublish) is likewise once per group, never per attachment.
 */
public final class UnstructuredIntakeLane extends AbstractIntakeLane<List<SourceRecord>, RawObjectEvent> {

    private final RawSourceType sourceType;
    private final SourceReader<List<SourceRecord>> sourceReader;
    private final PayloadMapper<List<SourceRecord>, RawObjectEvent> payloadMapper;
    private final PayloadValidator<RawObjectEvent> payloadValidator;
    private final PayloadPublisher<RawObjectEvent> payloadPublisher;
    private final IntakeLandingService landingService;
    private final GroupStateStore groupStateStore;
    private final CalendarEventStateStore calendarEventStateStore;

    UnstructuredIntakeLane(
            RawSourceType sourceType,
            SourceReader<List<SourceRecord>> sourceReader,
            PayloadMapper<List<SourceRecord>, RawObjectEvent> payloadMapper,
            PayloadValidator<RawObjectEvent> payloadValidator,
            PayloadPublisher<RawObjectEvent> payloadPublisher,
            IntakeLandingService landingService,
            GroupStateStore groupStateStore,
            CalendarEventStateStore calendarEventStateStore) {
        this.sourceType = sourceType;
        this.sourceReader = sourceReader;
        this.payloadMapper = payloadMapper;
        this.payloadValidator = payloadValidator;
        this.payloadPublisher = payloadPublisher;
        this.landingService = landingService;
        this.groupStateStore = groupStateStore;
        this.calendarEventStateStore = calendarEventStateStore;
    }

    public RawSourceType sourceType() {
        return sourceType;
    }

    @Override
    protected SourceReader<List<SourceRecord>> sourceReader() {
        return sourceReader;
    }

    @Override
    protected PayloadMapper<List<SourceRecord>, RawObjectEvent> payloadMapper() {
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

    /**
     * Lands every record in the group - positionally aligned with payload.objects(), since both
     * are built from the same group in the same order by UnstructuredPayloadMapper. Landing one
     * record's bytes failing (LaneException thrown, caught by AbstractIntakeLane.run()) means
     * none of this group's records reach validate/publish - a RawObjectEvent is only ever
     * published once every object it refers to has actually landed.
     * A record with a non-null precomputedRef (see SourceRecord's javadoc) was already landed
     * under a different group before this group was built - e.g. a migrated thread root's
     * attachment (see ChatSourceRecordMapper.toSourceRecords(GroupState, List)) - so it is skipped
     * here entirely rather than re-landed with its unused filler rawContent.
     */
    @Override
    protected void afterMap(List<SourceRecord> group, RawObjectEvent payload, Instant asOf) {
        try {
            List<RawObjectRef> objects = payload.objects();
            for (int i = 0; i < group.size(); i++) {
                if (group.get(i).precomputedRef() != null) {
                    continue;
                }
                landingService.land(objects.get(i).objectKey(), group.get(i), payload.scopeDecision(), asOf);
            }
        } catch (RuntimeException e) {
            throw new LaneException("Failed to land correlationId=" + payload.correlationId(), e);
        }
    }

    /**
     * Confirms the group's/event's publish state exactly once per group, using the group's
     * primary (body/transcript) record for identity - never per attachment, since the whole
     * group published as one Kafka event (see class javadoc).
     * Gated on primary.groupId() != null (only chat records built from a persisted GroupState
     * carry one - see SourceRecord's javadoc), so this is a no-op for every other source.
     * GroupStateStore's own failures surface as plain RuntimeExceptions (UncheckedIOException,
     * IllegalStateException), not LaneException, so they're wrapped here the same way afterMap
     * wraps landingService failures: run() only catches LaneException, and only counts this
     * group as published once afterPublish returns successfully.
     */
    @Override
    protected void afterPublish(List<SourceRecord> group, RawObjectEvent payload, Instant asOf) {
        SourceRecord primary = UnstructuredPayloadMapper.primaryOf(group);
        try {
            if (primary.groupId() != null) {
                groupStateStore.confirmPublished(
                        primary.sourceType(), primary.tenantId(), primary.groupId(), primary.revision());
            }
        } catch (RuntimeException e) {
            throw new LaneException(
                    "Failed to confirm chat group publish for correlationId=" + payload.correlationId(), e);
        }
        confirmCalendarEventPublish(primary);
    }

    /**
     * Confirms a Calendar event's persisted state once its group has actually been published -
     * the Calendar analog of the chat-group confirmation above. Calendar never splits a logical
     * item into attachments today, so its group is always a single record (see
     * RealCalendarSourceReader) and primary is simply that record.
     */
    private void confirmCalendarEventPublish(SourceRecord primary) {
        try {
            if (primary.sourceType() == RawSourceType.CALENDAR) {
                calendarEventStateStore.confirmPublished(primary.tenantId(), primary.naturalId(), primary.revision());
            }
        } catch (RuntimeException e) {
            throw new LaneException("Failed to confirm Calendar event publish for naturalId=" + primary.naturalId(), e);
        }
    }
}
