package com.rnd.bridge.intake.lane;

import com.rnd.bridge.core.PayloadMapper;
import com.rnd.bridge.core.error.LaneException;
import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.intake.service.SourceRecordRefMapper;
import com.rnd.bridge.raw.model.RawObjectEvent;
import com.rnd.bridge.raw.model.RawObjectRef;
import com.rnd.bridge.raw.model.ScopeDecision;
import com.rnd.bridge.scoping.AllowlistScopingFilter;
import java.time.Instant;
import java.util.List;

/**
 * Maps one correlationId-group of SourceRecords (a logical item's body/transcript plus every
 * attachment - see SourceRecordGrouper) to the single RawObjectEvent published for it, one
 * RawObjectRef per record via SourceRecordRefMapper. A pure transform - no I/O: landing the
 * actual bytes is a separate side effect per record, done afterward in lockstep with this
 * event's objects list (see UnstructuredIntakeLane#afterMap).
 * The group's outer fields (sourceType/tenantId/correlationId/revision/changeType/participants/
 * sourceTimestamp) all come from the group's primary record - the body/transcript
 * (attachmentIndex() == 0) - since every record in a correlationId-group shares them by
 * construction (see SourceRecord's javadoc) except scopeDecision, which has no per-record
 * equivalent on the wire anymore: the whole logical item's scope is decided from its primary
 * record's content, not voted on attachment-by-attachment.
 * Failures are wrapped in LaneException: AbstractIntakeLane's template method only catches
 * LaneException per record, so an unwrapped RuntimeException here would abort the whole run
 * instead of being recorded and skipped.
 */
final class UnstructuredPayloadMapper implements PayloadMapper<List<SourceRecord>, RawObjectEvent> {

    private final AllowlistScopingFilter scopingFilter;
    private final SourceRecordRefMapper refMapper;

    UnstructuredPayloadMapper(AllowlistScopingFilter scopingFilter, SourceRecordRefMapper refMapper) {
        this.scopingFilter = scopingFilter;
        this.refMapper = refMapper;
    }

    @Override
    public RawObjectEvent map(List<SourceRecord> group, Instant ingestedAt) {
        SourceRecord primary = primaryOf(group);
        try {
            ScopeDecision scopeDecision = scopingFilter.decide(primary);
            List<RawObjectRef> objects = group.stream().map(record -> refMapper.toRef(record, ingestedAt)).toList();

            return RawObjectEvent.create(
                    primary.sourceType(),
                    primary.tenantId(),
                    ingestedAt,
                    scopeDecision,
                    primary.participants(),
                    primary.occurredAt(),
                    primary.correlationId(),
                    primary.revision(),
                    primary.changeType(),
                    objects);
        } catch (RuntimeException e) {
            throw new LaneException(
                    "Failed to map sourceType=" + primary.sourceType() + " correlationId=" + primary.correlationId(), e);
        }
    }

    static SourceRecord primaryOf(List<SourceRecord> group) {
        return group.stream().filter(record -> record.attachmentIndex() == 0).findFirst().orElse(group.get(0));
    }
}
