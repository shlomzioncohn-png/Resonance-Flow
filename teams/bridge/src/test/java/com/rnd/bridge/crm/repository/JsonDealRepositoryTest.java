package com.rnd.bridge.crm.repository;

import com.rnd.bridge.crm.mapper.DealToTaskMapper;
import com.rnd.bridge.crm.model.Deal;
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

    private static final DealToTaskMapper MAPPER = new DealToTaskMapper();

    private DealRepository repository;

    @BeforeEach
    void setUp() {
        repository = new JsonDealRepository(new DefaultResourceLoader(), "classpath:crm/synthetic-deals.json");
    }

    @Test
    void loadsTheWholeFeed() {
        assertEquals(79, repository.findAll().size());
        assertEquals(32, repository.findAllDealIds().size());
        assertEquals(Instant.parse("2026-08-24T09:00:00Z"), repository.asOf());
    }

    @Test
    void returnsD10432HistoryInChronologicalOrder() {
        List<Deal> history = repository.findHistoryByDealId("D-10432");

        assertEquals(
                List.of(DealStage.PROPOSAL, DealStage.NEGOTIATION, DealStage.CLOSED_WON, DealStage.NEGOTIATION),
                history.stream().map(Deal::stage).toList());

        Optional<Deal> latest = repository.findLatestByDealId("D-10432");
        assertTrue(latest.isPresent());
        assertEquals(DealStage.NEGOTIATION, latest.get().stage());
        assertEquals(history.get(3), latest.get());
    }

    @Test
    void allD10432RecordsShareTheSameSourceRef() {
        List<Deal> history = repository.findHistoryByDealId("D-10432");

        Set<String> sourceRefs = history.stream()
                .map(MAPPER::toSourceRef)
                .collect(Collectors.toSet());

        assertEquals(1, sourceRefs.size());
        assertEquals("crm:D-10432", sourceRefs.iterator().next());
    }

    @Test
    void everyDealStageAppearsAsSomeDealsLatestStageAndMapsWithoutThrowing() {
        Set<DealStage> latestStages = EnumSet.noneOf(DealStage.class);

        for (String dealId : repository.findAllDealIds()) {
            Deal latest = repository.findLatestByDealId(dealId).orElseThrow();
            latestStages.add(latest.stage());
            assertDoesNotThrow(() -> DealToTaskMapper.toTaskStatus(latest.stage()));
        }

        assertEquals(EnumSet.allOf(DealStage.class), latestStages);
    }

    @Test
    void recordsWithNullOrZeroFieldsLoadWithoutThrowing() {
        Deal noCloseDate = repository.findLatestByDealId("D-10503").orElseThrow();
        assertNull(noCloseDate.closeDate());
        assertNull(noCloseDate.ownerEmail());
        assertNull(noCloseDate.dealValue());

        Deal zeroValue = repository.findLatestByDealId("D-10531").orElseThrow();
        assertEquals(0, zeroValue.dealValue().signum());
    }

    @Test
    void findAllIsNonDecreasingByStageChangedAt() {
        List<Deal> all = repository.findAll();

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
        List<String> dealIds = repository.findAllDealIds();

        long distinctCount = dealIds.stream().distinct().count();
        assertEquals(dealIds.size(), distinctCount, "findAllDealIds() must not contain duplicates");
        assertTrue(dealIds.size() < repository.findAll().size(),
                "there must be more stage-change records than distinct deals");
    }

    @Test
    void atLeastOneDealHasANullCloseDate() {
        boolean anyNullCloseDate = repository.findAllDealIds().stream()
                .map(id -> repository.findLatestByDealId(id).orElseThrow())
                .anyMatch(deal -> deal.closeDate() == null);

        assertTrue(anyNullCloseDate);
    }

    @Test
    void reopenedDealAfterClosedLostReturnsHistoryInOrderEndingOnThePostReopenStage() {
        List<Deal> history = repository.findHistoryByDealId("D-11007");

        assertEquals(
                List.of(DealStage.PROSPECT, DealStage.QUALIFIED, DealStage.CLOSED_LOST, DealStage.QUALIFIED),
                history.stream().map(Deal::stage).toList());

        Deal latest = repository.findLatestByDealId("D-11007").orElseThrow();
        assertEquals(DealStage.QUALIFIED, latest.stage());
        assertEquals(history.get(3), latest);
    }
}
