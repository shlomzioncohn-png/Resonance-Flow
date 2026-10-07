package com.rnd.bridge.crm.repository;

import com.rnd.bridge.crm.mapper.DealToTaskMapper;
import com.rnd.bridge.crm.model.Deal;
import com.rnd.bridge.task.enrichment.EnrichmentProperties;
import com.rnd.bridge.task.enrichment.NoOpTaskEnricher;
import com.rnd.bridge.crm.model.DealStage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonDealRepositoryTest {

    private static final DealToTaskMapper MAPPER = new DealToTaskMapper("org-test-0000", new EnrichmentProperties(), new NoOpTaskEnricher());

    private DealRepository repository;
    private DealSnapshot snapshot;

    @BeforeEach
    void setUp() {
        repository = new JsonDealRepository(new DefaultResourceLoader(), "classpath:crm/synthetic-deals.json");
        // JsonDealRepository.snapshot() returns the same fixed data every call (the fixture never
        // changes at runtime), so capturing it once here is equivalent to calling it per-assertion.
        snapshot = repository.snapshot();
    }

    @Test
    void loadsTheWholeFeed() {
        assertEquals(79, snapshot.allDeals().size());
        assertEquals(32, snapshot.dealIds().size());
        assertEquals(Instant.parse("2026-08-24T09:00:00Z"), snapshot.asOf());
    }

    @Test
    void returnsD10432HistoryInChronologicalOrder() {
        List<Deal> history = snapshot.findHistoryByDealId("D-10432");

        assertEquals(
                List.of(DealStage.PROPOSAL, DealStage.NEGOTIATION, DealStage.CLOSED_WON, DealStage.NEGOTIATION),
                history.stream().map(Deal::stage).toList());

        Optional<Deal> latest = snapshot.findLatestByDealId("D-10432");
        assertTrue(latest.isPresent());
        assertEquals(DealStage.NEGOTIATION, latest.get().stage());
        assertEquals(history.get(3), latest.get());
    }

    @Test
    void allD10432RecordsShareTheSameSourceRef() {
        List<Deal> history = snapshot.findHistoryByDealId("D-10432");

        Set<String> sourceRefs = history.stream()
                .map(MAPPER::toSourceRef)
                .collect(Collectors.toSet());

        assertEquals(1, sourceRefs.size());
        assertEquals("crm:D-10432", sourceRefs.iterator().next());
    }

    @Test
    void everyDealStageAppearsAsSomeDealsLatestStageAndMapsWithoutThrowing() {
        Set<DealStage> latestStages = EnumSet.noneOf(DealStage.class);

        for (String dealId : snapshot.dealIds()) {
            Deal latest = snapshot.findLatestByDealId(dealId).orElseThrow();
            latestStages.add(latest.stage());
            assertDoesNotThrow(() -> DealToTaskMapper.toTaskStatus(latest.stage()));
        }

        assertEquals(EnumSet.allOf(DealStage.class), latestStages);
    }

    @Test
    void recordsWithNullOrZeroFieldsLoadWithoutThrowing() {
        Deal noCloseDate = snapshot.findLatestByDealId("D-10503").orElseThrow();
        assertNull(noCloseDate.closeDate());
        assertNull(noCloseDate.ownerEmail());
        assertNull(noCloseDate.dealValue());

        Deal zeroValue = snapshot.findLatestByDealId("D-10531").orElseThrow();
        assertEquals(0, zeroValue.dealValue().signum());
    }

    @Test
    void allDealsIsNonDecreasingByStageChangedAt() {
        List<Deal> all = snapshot.allDeals();

        for (int i = 1; i < all.size(); i++) {
            assertFalse(all.get(i).stageChangedAt().isBefore(all.get(i - 1).stageChangedAt()),
                    "record at index " + i + " is out of order");
        }
    }

    @Test
    void missingDatasetLocationFailsFastWithLocationInMessage() {
        String badLocation = "classpath:crm/does-not-exist.json";

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> new JsonDealRepository(new DefaultResourceLoader(), badLocation));

        assertTrue(exception.getMessage().contains(badLocation));
    }

    @Test
    void dealIdsHaveNoDuplicatesAndAreFewerThanTotalRecords() {
        List<String> dealIds = snapshot.dealIds();

        long distinctCount = dealIds.stream().distinct().count();
        assertEquals(dealIds.size(), distinctCount, "dealIds() must not contain duplicates");
        assertTrue(dealIds.size() < snapshot.allDeals().size(),
                "there must be more stage-change records than distinct deals");
    }

    @Test
    void atLeastOneDealHasANullCloseDate() {
        boolean anyNullCloseDate = snapshot.dealIds().stream()
                .map(id -> snapshot.findLatestByDealId(id).orElseThrow())
                .anyMatch(deal -> deal.closeDate() == null);

        assertTrue(anyNullCloseDate);
    }

    @Test
    void reopenedDealAfterClosedLostReturnsHistoryInOrderEndingOnThePostReopenStage() {
        List<Deal> history = snapshot.findHistoryByDealId("D-11007");

        assertEquals(
                List.of(DealStage.PROSPECT, DealStage.QUALIFIED, DealStage.CLOSED_LOST, DealStage.QUALIFIED),
                history.stream().map(Deal::stage).toList());

        Deal latest = snapshot.findLatestByDealId("D-11007").orElseThrow();
        assertEquals(DealStage.QUALIFIED, latest.stage());
        assertEquals(history.get(3), latest);
    }

    @Test
    void repeatedSnapshotCallsReturnTheSameFixedDataNoFetchToRepeat() {
        // There's no "fresh fetch" to prove here the way there is for HubSpotDealRepository - the
        // fixture is loaded once at construction, so snapshot() has nothing to refresh. This just
        // pins that calling it again doesn't produce different (or differently-ordered) data.
        assertEquals(snapshot, repository.snapshot());
    }
}
