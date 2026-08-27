package com.rnd.bridge.intake.service;

import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.intake.parser.SourceRecordLoader;
import com.rnd.bridge.raw.model.RawObjectEvent;
import com.rnd.bridge.raw.model.RawSourceType;
import com.rnd.bridge.raw.model.ScopeDecision;
import com.rnd.bridge.scoping.AllowlistScopingFilter;
import com.rnd.bridge.storage.ObjectKeyBuilder;
import com.rnd.bridge.storage.ObjectStorageClient.PutResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Orchestrates one source end to end: load -> per record: scope -> build key -> land ->
 * publish. ingestedAt is computed once per record and threaded through to the key
 * builder, the MinIO tag, and the published event, so all three agree on the same instant
 * instead of each independently calling Instant.now().
 * Deliberately stays a plain orchestrator rather than an AbstractIntakeLane subclass: a
 * Lane is bound to one external source (see CrmLane/Deal), but this pipeline serves six
 * RawSourceType values behind one runtime-selected path, and the map/validate/publish
 * steps are identical across all six — splitting into six near-duplicate Lane subclasses
 * would add boilerplate without the benefit Template Method is meant to provide (that
 * benefit shows up when implementations genuinely differ, as with CRM vs GitHub, not here).
 */
@Service
public class UnstructuredIntakeService {

    private final SourceRecordLoader sourceLoader;
    private final AllowlistScopingFilter scopingFilter;
    private final ObjectKeyBuilder objectKeyBuilder;
    private final IntakeLandingService landingService;
    private final PointerEventPublisher pointerEventPublisher;

    public UnstructuredIntakeService(
            SourceRecordLoader sourceLoader,
            AllowlistScopingFilter scopingFilter,
            ObjectKeyBuilder objectKeyBuilder,
            IntakeLandingService landingService,
            PointerEventPublisher pointerEventPublisher) {
        this.sourceLoader = sourceLoader;
        this.scopingFilter = scopingFilter;
        this.objectKeyBuilder = objectKeyBuilder;
        this.landingService = landingService;
        this.pointerEventPublisher = pointerEventPublisher;
    }

    public List<RawObjectEvent> run(RawSourceType sourceType) {
        List<SourceRecord> records = sourceLoader.load(sourceType);

        List<RawObjectEvent> events = new ArrayList<>();
        for (SourceRecord record : records) {
            Instant ingestedAt = Instant.now();
            ScopeDecision scopeDecision = scopingFilter.decide(record);
            String objectKey = objectKeyBuilder.build(record, ingestedAt);
            PutResult putResult = landingService.land(objectKey, record, scopeDecision, ingestedAt);
            RawObjectEvent event = pointerEventPublisher.publish(
                    objectKey, record, scopeDecision, putResult, ingestedAt);
            events.add(event);
        }
        return events;
    }
}
