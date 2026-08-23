package com.rnd.bridge.intake.unstructured.service;

import com.rnd.bridge.intake.unstructured.dto.SourceRecord;
import com.rnd.bridge.intake.unstructured.fixture.FixtureSourceLoader;
import com.rnd.bridge.intake.unstructured.service.MinioLandingService.LandingResult;
import com.rnd.bridge.shared.unstructured.dto.RawObjectEvent;
import com.rnd.bridge.shared.unstructured.dto.ScopeDecision;
import com.rnd.bridge.shared.unstructured.dto.SourceType;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Orchestrates one source end to end: load -> per record: scope -> build key -> land ->
 * publish.
 */
@Service
public class UnstructuredIntakeService {

    private final FixtureSourceLoader sourceLoader;
    private final ScopingFilterService scopingFilterService;
    private final ObjectKeyBuilder objectKeyBuilder;
    private final MinioLandingService landingService;
    private final PointerEventPublisher pointerEventPublisher;

    public UnstructuredIntakeService(
            FixtureSourceLoader sourceLoader,
            ScopingFilterService scopingFilterService,
            ObjectKeyBuilder objectKeyBuilder,
            MinioLandingService landingService,
            PointerEventPublisher pointerEventPublisher) {
        this.sourceLoader = sourceLoader;
        this.scopingFilterService = scopingFilterService;
        this.objectKeyBuilder = objectKeyBuilder;
        this.landingService = landingService;
        this.pointerEventPublisher = pointerEventPublisher;
    }

    public List<RawObjectEvent> run(SourceType sourceType) {
        List<SourceRecord> records = sourceLoader.load(sourceType);

        List<RawObjectEvent> events = new ArrayList<>();
        for (SourceRecord record : records) {
            ScopeDecision scopeDecision = scopingFilterService.decide(record);
            String objectKey = objectKeyBuilder.build(record);
            LandingResult landingResult = landingService.land(objectKey, record, scopeDecision);
            RawObjectEvent event =
                    pointerEventPublisher.publish(objectKey, record, scopeDecision, landingResult);
            events.add(event);
        }
        return events;
    }
}
