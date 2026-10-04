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
    private final CalendarEventStateStore calendarEventStateStore;

    UnstructuredIntakeLane(
            RawSourceType sourceType,
            SourceReader<SourceRecord> sourceReader,
            PayloadMapper<SourceRecord, RawObjectEvent> payloadMapper,
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
     * transcript/body record, never an attachment) and a non-null source.groupId() (only chat
     * records built from a persisted group carry one - see SourceRecord's javadoc), so this is a
     * no-op for every other source and for attachment records.
     * source.groupId() is ChatGroupStateReconciler's own internal GroupState.groupId, carried
     * straight through from the GroupState that produced this record (see SourceRecord's
     * javadoc) - not reverse-resolved through GroupStateStore's messageId index. That index is
     * for recognizing an edit/re-read of an already-landed message (see GroupStateStore's class
     * javadoc), not for answering "which group produced this SourceRecord": resolving via the
     * index used to break for a thread's root message specifically, since
     * GroupStateStore.writeMessageIndex deliberately never indexes it (see that method's
     * javadoc) - a thread transcript's sourceMessageIds is always root-first (sorted by
     * timestamp), so looking the confirmation target up via sourceMessageIds().get(0) silently
     * never resolved for any thread with a reply, leaving the group's published flag stuck at
     * false and every later edit permanently collapsing onto revision 0 instead of advancing.
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
            if (source.attachmentIndex() == 0 && source.groupId() != null) {
                groupStateStore.confirmPublished(
                        source.sourceType(), source.tenantId(), source.groupId(), source.revision());
            } else if (source.attachmentIndex() > 0
                    && source.attachmentFileId() != null
                    && source.groupId() != null
                    && !source.sourceMessageIds().isEmpty()) {
                // Mirrors the transcript confirmation above, at per-attachment granularity - see
                // GroupStateStore.confirmAttachmentPublished's javadoc for why this is keyed on
                // Slack's own stable file id rather than this record's attachmentIndex. The
                // owning message id itself still comes from sourceMessageIds (an attachment
                // record's single element, always that specific message's own id, never the
                // group's root) - only the GROUP identity now comes from source.groupId()
                // instead of a reverse messageId-index lookup.
                String messageId = source.sourceMessageIds().get(0);
                groupStateStore.confirmAttachmentPublished(
                        source.sourceType(), source.tenantId(), source.groupId(), messageId, source.attachmentFileId());
            }
        } catch (RuntimeException e) {
            throw new LaneException(
                    "Failed to confirm chat group publish for sourceType=" + source.sourceType()
                            + " naturalId=" + source.naturalId(), e);
        }
        confirmCalendarEventPublish(source);
    }

    /**
     * Confirms a Calendar event's persisted state once its SourceRecord has actually been
     * published - the Calendar analog of the chat-group confirmation above (see
     * CalendarEventStateReconciler/CalendarEventStateStore for the identity/revision side of
     * this). source.naturalId() is event.getId() for every Calendar record (see
     * RealCalendarSourceReader.toSourceRecord/FixtureCalendarSourceReader - Calendar attachments
     * aren't implemented yet, so attachmentIndex is always 0 and naturalId is never suffixed).
     * source.revision() is passed through as the expected revision so a stale ack for a
     * since-superseded revision can never mark a newer, not-yet-published one as confirmed - same
     * expected-revision guard as GroupStateStore.confirmPublished, for the same reason.
     * Gated on sourceType() rather than a Calendar-specific SourceReader/record type, matching
     * how the chat-group branch above is gated on sourceMessageIds() - so this stays a no-op for
     * every other source without needing a source-specific SourceRecord subtype.
     */
    private void confirmCalendarEventPublish(SourceRecord source) {
        try {
            if (source.sourceType() == RawSourceType.CALENDAR) {
                calendarEventStateStore.confirmPublished(source.tenantId(), source.naturalId(), source.revision());
            }
        } catch (RuntimeException e) {
            throw new LaneException(
                    "Failed to confirm Calendar event publish for naturalId=" + source.naturalId(), e);
        }
    }
}
