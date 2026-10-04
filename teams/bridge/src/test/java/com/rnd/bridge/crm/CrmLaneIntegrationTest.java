package com.rnd.bridge.crm;

import com.rnd.bridge.core.IntakeResult;
import com.rnd.bridge.core.ReadCursor;
import com.rnd.bridge.crm.mapper.DealToTaskMapper;
import com.rnd.bridge.crm.repository.DealRepository;
import com.rnd.bridge.crm.repository.JsonDealRepository;
import com.rnd.bridge.publish.LoggingPayloadPublisher;
import com.rnd.bridge.task.TaskValidator;
import com.rnd.bridge.task.enrichment.EnrichmentProperties;
import com.rnd.bridge.task.enrichment.NoOpTaskEnricher;
import com.rnd.bridge.task.model.Task;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole pipeline (real dataset -> real mapper -> real validator ->
 * logging publisher) run end to end for the first time. If this fails, it
 * is a real finding about the mapper or the dataset, not a test to relax.
 */
class CrmLaneIntegrationTest {

    private static DealRepository repository() {
        return new JsonDealRepository(new DefaultResourceLoader(), "classpath:crm/synthetic-deals.json");
    }

    private static CrmLane newLane(DealRepository repository) {
        return new CrmLane(
                new CrmSourceReader(repository),
                new DealToTaskMapper("org-test-0000", new EnrichmentProperties(), new NoOpTaskEnricher()),
                new TaskValidator(),
                new LoggingPayloadPublisher<Task>());
    }

    @Test
    void everyRecordInTheSyntheticDatasetMapsToAValidTask() {
        DealRepository repository = repository();
        CrmLane lane = newLane(repository);

        IntakeResult result = lane.run(ReadCursor.full(), repository.asOf());

        int expected = repository.findAll().size();

        assertEquals(expected, result.read());
        assertTrue(result.errors().isEmpty(),
                () -> "expected no rejections, got: " + result.errors());
        assertEquals(0, result.rejected());
        assertEquals(expected, result.published());
    }

    @Test
    void theRunIsDeterministicAcrossRepeatedInvocations() {
        DealRepository repository = repository();
        Instant asOf = repository.asOf();

        IntakeResult first = newLane(repository).run(ReadCursor.full(), asOf);
        IntakeResult second = newLane(repository).run(ReadCursor.full(), asOf);

        assertEquals(first.read(), second.read());
        assertEquals(first.published(), second.published());
        assertEquals(first.rejected(), second.rejected());
    }
}
