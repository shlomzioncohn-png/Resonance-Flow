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
import com.rnd.bridge.task.model.TaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole pipeline (real dataset -> real mapper -> real validator ->
 * logging publisher) run end to end for the first time. If this fails, it
 * is a real finding about the mapper or the dataset, not a test to relax.
 * <p>
 * Publishes one {@link Task} per DEAL, not one per stage-history row: the synthetic dataset has 79
 * stage-change records across 32 deals (see its own {@code notes} field), and
 * {@code CrmSourceReader} now reads {@code snapshot().latestPerDeal()}. Before this change the lane
 * published all 79 rows, and because Inbox's ingestion for this topic is insert-or-return-existing
 * (not an upsert), it kept whichever record for a given {@code sourceRef} arrived FIRST - the
 * deal's oldest state, not its current one. See finding F2 in the CRM-lane audit; this test used to
 * assert 79 publishes and is updated deliberately here, not silently, because that old number was
 * itself evidence of the bug.
 */
class CrmLaneIntegrationTest {

    private static final String VALID_TENANT_ID = "11111111-1111-1111-1111-111111111111";

    private static DealRepository repository() {
        return new JsonDealRepository(new DefaultResourceLoader(), "classpath:crm/synthetic-deals.json");
    }

    private static CrmLane newLane(DealRepository repository) {
        return new CrmLane(
                new CrmSourceReader(repository),
                new DealToTaskMapper(VALID_TENANT_ID, new EnrichmentProperties(), new NoOpTaskEnricher()),
                new TaskValidator(),
                new LoggingPayloadPublisher<Task>());
    }

    @Test
    void everyDealInTheSyntheticDatasetMapsToAValidTask() {
        DealRepository repository = repository();
        CrmLane lane = newLane(repository);

        IntakeResult result = lane.run(ReadCursor.full(), repository.snapshot().asOf());

        int expectedDealCount = repository.snapshot().dealIds().size();
        assertEquals(32, expectedDealCount, "the synthetic dataset's own deal count moved - "
                + "update this test's expectations deliberately if that's intentional");

        assertEquals(expectedDealCount, result.read());
        assertTrue(result.errors().isEmpty(),
                () -> "expected no rejections, got: " + result.errors());
        assertEquals(0, result.rejected());
        assertEquals(expectedDealCount, result.published());
    }

    @Test
    void theRunIsDeterministicAcrossRepeatedInvocations() {
        DealRepository repository = repository();
        Instant asOf = repository.snapshot().asOf();

        IntakeResult first = newLane(repository).run(ReadCursor.full(), asOf);
        IntakeResult second = newLane(repository).run(ReadCursor.full(), asOf);

        assertEquals(first.read(), second.read());
        assertEquals(first.published(), second.published());
        assertEquals(first.rejected(), second.rejected());
    }

    @Test
    void aFullyProgressedDealEndsUpMappedAsDoneNotItsFirstStage() {
        // D-11009: a full five-event PROSPECT -> ... -> CLOSED_WON progression (see the dataset's
        // own "notes"). Only its latest state should ever be mapped/published.
        DealToTaskMapper mapper =
                new DealToTaskMapper(VALID_TENANT_ID, new EnrichmentProperties(), new NoOpTaskEnricher());
        DealRepository repository = repository();
        Instant asOf = repository.snapshot().asOf();

        Task task = mapper.map(repository.snapshot().findLatestByDealId("D-11009").orElseThrow(), asOf);

        assertEquals(TaskStatus.DONE, task.status());
        assertEquals(1, task.urgency(), "a closed deal gets no time-pressure bonus");
    }

    @Test
    void reopenedDealsPublishTheirPostReopenStateNotTheirFirstOrIntermediateStage() {
        // D-10432: CLOSED_WON -> NEGOTIATION (re-opened, close date pushed out to 2026-09-30).
        // D-11007: CLOSED_LOST -> QUALIFIED (re-opened, close date 2026-10-05). Both ultimately map
        // to NEW - publishing the FIRST record for either (the pre-F2 behavior) would also have
        // looked like NEW at the Task level, which is exactly why this bug was easy to miss without
        // checking what Inbox's insert-or-return-existing ingestion actually keeps: a record with
        // the wrong urgency, computed from the wrong stage and the wrong close date.
        DealToTaskMapper mapper =
                new DealToTaskMapper(VALID_TENANT_ID, new EnrichmentProperties(), new NoOpTaskEnricher());
        DealRepository repository = repository();
        Instant asOf = repository.snapshot().asOf(); // 2026-08-24T09:00:00Z

        Task d10432 = mapper.map(repository.snapshot().findLatestByDealId("D-10432").orElseThrow(), asOf);
        Task d11007 = mapper.map(repository.snapshot().findLatestByDealId("D-11007").orElseThrow(), asOf);

        assertEquals(TaskStatus.NEW, d10432.status());
        assertEquals(4, d10432.urgency(), "NEGOTIATION base=4, close date 37 days out gets no bonus");

        assertEquals(TaskStatus.NEW, d11007.status());
        assertEquals(2, d11007.urgency(), "QUALIFIED base=2, close date 42 days out gets no bonus");
    }
}
