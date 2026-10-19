package com.rnd.bridge.intake.service;

import com.rnd.bridge.core.PayloadPublisher;
import com.rnd.bridge.intake.lane.GroupStateStore;
import com.rnd.bridge.intake.model.GroupState;
import com.rnd.bridge.intake.model.LandedAttachment;
import com.rnd.bridge.intake.model.MessageSnapshot;
import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.raw.RawObjectValidator;
import com.rnd.bridge.raw.model.RawObjectEvent;
import com.rnd.bridge.raw.model.RawObjectRef;
import com.rnd.bridge.raw.model.ScopeDecision;
import com.rnd.bridge.scoping.AllowlistScopingFilter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Assembles and publishes the ONE RawObjectEvent for a Slack non-thread session once it closes
 * (called from SlackSessionScheduler's deadline callback): the transcript (landed here, fresh)
 * plus every attachment already landed during the session (see
 * GroupStateStore.recordLandedAttachment / MessageSnapshot.landedAttachments) - never a partial
 * object list, since every attachment ref here was already durably landed to MinIO before this
 * method ever runs. Pulled out of SlackSessionScheduler so that class stays focused purely on
 * timing/registry concerns (see its javadoc) - this class owns the actual land/validate/
 * publish/confirm sequence, mirroring what UnstructuredIntakeLane does per correlationId-group
 * for every other source/path, just without going through AbstractIntakeLane itself (the
 * attachment bytes aren't available here to re-land through the generic per-record flow - only
 * their already-computed refs are).
 */
@Service
public class SlackSessionPublisher {

    private final AllowlistScopingFilter scopingFilter;
    private final SourceRecordRefMapper refMapper;
    private final IntakeLandingService landingService;
    private final RawObjectValidator validator;
    private final PayloadPublisher<RawObjectEvent> publisher;
    private final GroupStateStore groupStateStore;
    private final ChatSourceRecordMapper chatSourceRecordMapper;

    public SlackSessionPublisher(
            AllowlistScopingFilter scopingFilter, SourceRecordRefMapper refMapper, IntakeLandingService landingService,
            RawObjectValidator validator, PayloadPublisher<RawObjectEvent> publisher, GroupStateStore groupStateStore,
            ChatSourceRecordMapper chatSourceRecordMapper) {
        this.scopingFilter = scopingFilter;
        this.refMapper = refMapper;
        this.landingService = landingService;
        this.validator = validator;
        this.publisher = publisher;
        this.groupStateStore = groupStateStore;
        this.chatSourceRecordMapper = chatSourceRecordMapper;
    }

    /**
     * newMessagesThisBatch is List.of() because this session's attachments were already landed
     * individually when their owning message first arrived (see
     * ChatGroupStateReconciler.reconcileNonThreadedMessage) - re-passing them here would just
     * rebuild attachment SourceRecords this method has no use for (their bytes aren't needed
     * again; their refs are already on state.messages()). With an empty newMessagesThisBatch,
     * ChatSourceRecordMapper.toSourceRecords only ever emits the transcript record itself
     * (attachmentIndex() == 0); the filter below is an explicit belt-and-suspenders check against
     * that existing convention, not a new one.
     */
    public void publishSession(GroupState state, Instant asOf) {
        List<SourceRecord> transcriptOnly = chatSourceRecordMapper.toSourceRecords(state, List.of()).stream()
                .filter(record -> record.attachmentIndex() == 0)
                .toList();
        if (transcriptOnly.isEmpty()) {
            return;
        }
        SourceRecord transcript = transcriptOnly.get(0);

        ScopeDecision scopeDecision = scopingFilter.decide(transcript);
        RawObjectRef transcriptRef = refMapper.toRef(transcript, asOf);
        landingService.land(transcriptRef.objectKey(), transcript, scopeDecision, asOf);

        List<RawObjectRef> objects = new ArrayList<>();
        objects.add(transcriptRef);
        for (MessageSnapshot message : state.messages()) {
            for (LandedAttachment landed : message.landedAttachments()) {
                objects.add(landed.ref());
            }
        }

        RawObjectEvent event = RawObjectEvent.create(
                transcript.sourceType(), transcript.tenantId(), asOf, scopeDecision, transcript.participants(),
                transcript.occurredAt(), transcript.correlationId(), transcript.revision(), transcript.changeType(),
                objects);

        validator.validate(event);
        publisher.publish(event);
        groupStateStore.confirmPublished(state.sourceType(), state.tenantId(), state.groupId(), state.revision());
    }
}
