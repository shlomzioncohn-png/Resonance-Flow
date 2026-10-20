package com.rnd.bridge.crm;

import com.rnd.bridge.core.IntakeResult;
import com.rnd.bridge.core.PayloadPublisher;
import com.rnd.bridge.crm.mapper.DealToTaskMapper;
import com.rnd.bridge.crm.model.Deal;
import com.rnd.bridge.crm.model.DealStage;
import com.rnd.bridge.crm.repository.DealRepository;
import com.rnd.bridge.crm.repository.DealSnapshot;
import com.rnd.bridge.task.TaskValidator;
import com.rnd.bridge.task.enrichment.EnrichmentProperties;
import com.rnd.bridge.task.enrichment.NoOpTaskEnricher;
import com.rnd.bridge.task.model.Task;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CrmSyncService} orchestration: sequential per-source runs, own snapshot/asOf per source,
 * and failure isolation. Uses hand-written {@link DealRepository} fakes, not real HubSpot/Salesforce.
 */
class CrmSyncServiceTest {

    private static final String TENANT_ID = "11111111-1111-1111-1111-111111111111";

    private static Deal dealWithPrefix(String dealId, String prefix, Instant stageChangedAt) {
        return Deal.builder()
                .dealId(dealId)
                .sourceRefPrefix(prefix)
                .stage(DealStage.PROSPECT)
                .stageChangedAt(stageChangedAt)
                .build();
    }

    private static CrmLane newLane(PayloadPublisher<Task> publisher) {
        return new CrmLane(
                mock(CrmSourceReader.class),
                new DealToTaskMapper(TENANT_ID, new EnrichmentProperties(), new NoOpTaskEnricher(), 0),
                new CrmTaskValidator(new TaskValidator()),
                publisher);
    }

    private static final class CapturingPublisher implements PayloadPublisher<Task> {
        private final List<Task> published = new ArrayList<>();

        @Override
        public void publish(Task payload) {
            published.add(payload);
        }
    }

    private static final class FakeDealRepository implements DealRepository {
        private final String source;
        private final List<Deal> deals;
        private final Instant asOf;
        private final int skippedAtRead;
        private final RuntimeException failure;

        private FakeDealRepository(
                String source, List<Deal> deals, Instant asOf, int skippedAtRead, RuntimeException failure) {
            this.source = source;
            this.deals = deals;
            this.asOf = asOf;
            this.skippedAtRead = skippedAtRead;
            this.failure = failure;
        }

        static FakeDealRepository of(String source, Instant asOf, int skippedAtRead, Deal... deals) {
            return new FakeDealRepository(source, List.of(deals), asOf, skippedAtRead, null);
        }

        static FakeDealRepository failing(String source, RuntimeException failure) {
            return new FakeDealRepository(source, List.of(), null, 0, failure);
        }

        @Override
        public String source() {
            return source;
        }

        @Override
        public DealSnapshot snapshot() {
            if (failure != null) {
                throw failure;
            }
            Map<String, List<Deal>> history = new LinkedHashMap<>();
            List<String> dealIds = new ArrayList<>();
            for (Deal deal : deals) {
                history.put(deal.dealId(), List.of(deal));
                dealIds.add(deal.dealId());
            }
            return new DealSnapshot(asOf, deals, history, dealIds, skippedAtRead);
        }

        @Override
        public Optional<Deal> fetchDeal(String dealId) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void bothSourcesRunInOneCycleInOrderAllPublishedWithDistinctPrefixes() {
        Instant hubspotAsOf = Instant.parse("2026-08-24T09:00:00Z");
        Instant salesforceAsOf = Instant.parse("2026-08-25T09:00:00Z");
        FakeDealRepository hubspot = FakeDealRepository.of(
                "hubspot", hubspotAsOf, 0, dealWithPrefix("D-1", "crm:hubspot:", hubspotAsOf));
        FakeDealRepository salesforce = FakeDealRepository.of(
                "salesforce", salesforceAsOf, 0, dealWithPrefix("D-2", "crm:salesforce:", salesforceAsOf));

        CapturingPublisher publisher = new CapturingPublisher();
        CrmSyncService service = new CrmSyncService(List.of(hubspot, salesforce), newLane(publisher));

        List<CrmSourceResult> results = service.syncAll();

        assertEquals(2, results.size());
        assertEquals("hubspot", results.get(0).source(), "sources run in registration order");
        assertEquals("salesforce", results.get(1).source());
        assertEquals(1, results.get(0).published());
        assertEquals(1, results.get(1).published());
        assertNull(results.get(0).error());
        assertNull(results.get(1).error());

        Set<String> sourceRefs = publisher.published.stream().map(Task::sourceRef).collect(Collectors.toSet());
        assertEquals(Set.of("crm:hubspot:D-1", "crm:salesforce:D-2"), sourceRefs);
    }

    @Test
    void sameRawDealIdInBothCrmsProducesTwoDistinctSourceRefsBothPublished() {
        Instant asOf = Instant.parse("2026-08-24T09:00:00Z");
        FakeDealRepository hubspot = FakeDealRepository.of(
                "hubspot", asOf, 0, dealWithPrefix("D-1", "crm:hubspot:", asOf));
        FakeDealRepository salesforce = FakeDealRepository.of(
                "salesforce", asOf, 0, dealWithPrefix("D-1", "crm:salesforce:", asOf));

        CapturingPublisher publisher = new CapturingPublisher();
        new CrmSyncService(List.of(hubspot, salesforce), newLane(publisher)).syncAll();

        assertEquals(2, publisher.published.size());
        Set<String> sourceRefs = publisher.published.stream().map(Task::sourceRef).collect(Collectors.toSet());
        assertEquals(Set.of("crm:hubspot:D-1", "crm:salesforce:D-1"), sourceRefs);
    }

    @Test
    void eachSourcesLaneRunReceivesItsOwnAsOf() {
        Instant hubspotAsOf = Instant.parse("2026-08-24T09:00:00Z");
        Instant salesforceAsOf = Instant.parse("2026-09-01T00:00:00Z");
        FakeDealRepository hubspot = FakeDealRepository.of("hubspot", hubspotAsOf, 0);
        FakeDealRepository salesforce = FakeDealRepository.of("salesforce", salesforceAsOf, 0);

        CrmLane crmLane = mock(CrmLane.class);
        when(crmLane.run(any(Stream.class), any())).thenReturn(new IntakeResult(0, 0, 0, List.of()));

        new CrmSyncService(List.of(hubspot, salesforce), crmLane).syncAll();

        verify(crmLane).run(any(Stream.class), eq(hubspotAsOf));
        verify(crmLane).run(any(Stream.class), eq(salesforceAsOf));
    }

    @Test
    void firstSourceThrowsSecondSourceStillPublishes() {
        Instant asOf = Instant.parse("2026-08-24T09:00:00Z");
        FakeDealRepository hubspot = FakeDealRepository.failing("hubspot", new RuntimeException("HubSpot unreachable"));
        FakeDealRepository salesforce = FakeDealRepository.of(
                "salesforce", asOf, 0, dealWithPrefix("D-9", "crm:salesforce:", asOf));

        CapturingPublisher publisher = new CapturingPublisher();
        List<CrmSourceResult> results =
                new CrmSyncService(List.of(hubspot, salesforce), newLane(publisher)).syncAll();

        assertEquals("HubSpot unreachable", results.get(0).error());
        assertEquals(0, results.get(0).published());
        assertNull(results.get(1).error());
        assertEquals(1, results.get(1).published());
        assertEquals(1, publisher.published.size());
    }

    @Test
    void bothSourcesThrowBothResultsCarryTheirOwnError() {
        FakeDealRepository hubspot = FakeDealRepository.failing("hubspot", new RuntimeException("HubSpot unreachable"));
        FakeDealRepository salesforce =
                FakeDealRepository.failing("salesforce", new RuntimeException("Salesforce unreachable"));

        List<CrmSourceResult> results =
                new CrmSyncService(List.of(hubspot, salesforce), mock(CrmLane.class)).syncAll();

        assertEquals("HubSpot unreachable", results.get(0).error());
        assertEquals("Salesforce unreachable", results.get(1).error());
        assertEquals(0, results.get(0).published());
        assertEquals(0, results.get(1).published());
    }

    @Test
    void skippedAtReadIsReportedPerSource() {
        Instant asOf = Instant.parse("2026-08-24T09:00:00Z");
        FakeDealRepository hubspot = FakeDealRepository.of("hubspot", asOf, 3);
        FakeDealRepository salesforce = FakeDealRepository.of("salesforce", asOf, 5);

        List<CrmSourceResult> results = new CrmSyncService(List.of(hubspot, salesforce), newLane(new CapturingPublisher()))
                .syncAll();

        assertEquals(3, results.get(0).skippedAtRead());
        assertEquals(5, results.get(1).skippedAtRead());
    }

    @Test
    void zeroRepositoriesProducesAnEmptyResultList() {
        List<CrmSourceResult> results = new CrmSyncService(List.of(), mock(CrmLane.class)).syncAll();

        assertEquals(List.of(), results);
    }
}
