package com.rnd.bridge.crm.mapper;

import com.rnd.bridge.crm.model.Deal;
import com.rnd.bridge.crm.model.DealStage;
import com.rnd.bridge.dto.ExtractionProvenance;
import com.rnd.bridge.dto.RoleContext;
import com.rnd.bridge.dto.SourceType;
import com.rnd.bridge.dto.Task;
import com.rnd.bridge.dto.TaskStatus;
import com.rnd.bridge.dto.Tier;
import com.rnd.bridge.repository.JsonDealRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.DefaultResourceLoader;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DealToTaskMapperTest {

    private static final Instant AS_OF = Instant.parse("2026-08-23T00:00:00Z");

    private static JsonDealRepository repository() {
        return new JsonDealRepository(new DefaultResourceLoader(), "classpath:crm/synthetic-deals.json");
    }

    private static Deal findLatest(JsonDealRepository repo, String dealId) {
        return repo.findLatestByDealId(dealId).orElseThrow();
    }

    @Test
    void dealStageHasExactlySevenValues() {
        // Tripwire: the compiler forces a new switch arm in toTaskStatus()
        // when a stage is added, but nothing forces a matching row in the
        // CsvSource below. This fails loudly instead of silently under-covering.
        assertEquals(7, DealStage.values().length);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "PROSPECT,    NEW",
            "QUALIFIED,   NEW",
            "PROPOSAL,    NEW",
            "NEGOTIATION, NEW",
            "ON_HOLD,     SNOOZED",
            "CLOSED_WON,  DONE",
            "CLOSED_LOST, CANCELLED"
    })
    void mapsEveryDealStageToExpectedTaskStatus(DealStage stage, TaskStatus expected) {
        assertEquals(expected, DealToTaskMapper.toTaskStatus(stage));
    }

    @Test
    void reopenedDealRevivesTaskStatusBackToNew() {
        Instant wonAt = Instant.parse("2026-08-20T10:00:00Z");
        Instant reopenedAt = wonAt.plus(1, ChronoUnit.DAYS);

        Deal won = Deal.builder().dealId("D-10432").stage(DealStage.CLOSED_WON).stageChangedAt(wonAt).build();
        Deal reopened = Deal.builder().dealId("D-10432").stage(DealStage.NEGOTIATION).stageChangedAt(reopenedAt).build();

        assertEquals(TaskStatus.DONE, DealToTaskMapper.toTaskStatus(won.stage()));
        assertEquals(TaskStatus.NEW, DealToTaskMapper.toTaskStatus(reopened.stage()));

        // Same dealId must still resolve to the same dedup key after the
        // reopen - otherwise the reopened deal creates a second, invisible
        // Task instead of reviving the existing one.
        assertEquals(DealToTaskMapper.toSourceRef(won), DealToTaskMapper.toSourceRef(reopened));
    }

    @Test
    void sameDealYieldsIdenticalSourceRefOnRepeatedMapping() {
        Deal deal = Deal.builder().dealId("D-10432").stage(DealStage.NEGOTIATION).stageChangedAt(Instant.now()).build();

        assertEquals(DealToTaskMapper.toSourceRef(deal), DealToTaskMapper.toSourceRef(deal));
    }

    @Test
    void sourceRefIsDerivedFromDealIdAloneNotFromStageOrTimestampOrOwner() {
        Deal before = Deal.builder().dealId("D-10432").stage(DealStage.PROPOSAL).stageChangedAt(Instant.now()).build();
        Deal after = Deal.builder()
                .dealId("D-10432")
                .stage(DealStage.CLOSED_WON)
                .ownerEmail("owner@example.com")
                .stageChangedAt(Instant.now().plus(1, ChronoUnit.HOURS))
                .build();

        assertEquals(DealToTaskMapper.toSourceRef(before), DealToTaskMapper.toSourceRef(after));
    }

    @Test
    void differentDealsYieldDifferentSourceRefs() {
        Deal a = Deal.builder().dealId("D-10432").stage(DealStage.PROSPECT).stageChangedAt(Instant.now()).build();
        Deal b = Deal.builder().dealId("D-99999").stage(DealStage.PROSPECT).stageChangedAt(Instant.now()).build();

        assertNotEquals(DealToTaskMapper.toSourceRef(a), DealToTaskMapper.toSourceRef(b));
    }

    @Test
    void toTaskMapsEveryFieldOnAFullyPopulatedDeal() {
        Deal deal = Deal.builder()
                .dealId("D-90001")
                .title("Full deal title")
                .description("Full deal description")
                .stage(DealStage.NEGOTIATION)
                .ownerEmail("owner@example.com")
                .ownerName("Owner Name")
                .closeDate(LocalDate.of(2026, 8, 26))
                .dealValue(new BigDecimal("1000"))
                .currency("USD")
                .crmAccountSlug("acme")
                .sourceUrl("https://crm.example.internal/deals/D-90001")
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .stageChangedAt(Instant.parse("2026-08-20T00:00:00Z"))
                .build();

        Task task = DealToTaskMapper.toTask(deal, AS_OF);

        assertEquals("Full deal title", task.title());
        assertEquals("Full deal description", task.description());
        assertEquals(SourceType.CRM_DEAL, task.sourceType());
        assertEquals("crm:D-90001", task.sourceRef());
        assertEquals(RoleContext.SALESPERSON, task.roleContext());
        assertEquals(Tier.TACTICAL, task.tier());
        assertEquals(TaskStatus.NEW, task.status());
        assertEquals(5, task.urgency()); // NEGOTIATION base 4 + close-in-3-days bonus 2, clamped to 5
        assertEquals(
                LocalDate.of(2026, 8, 26).atTime(LocalTime.MAX).toInstant(ZoneOffset.UTC),
                task.deadline());
        assertEquals(ExtractionProvenance.ETL, task.extractionProvenance());
        assertNull(task.confidence());
        assertNull(task.estimatedDurationMinutes());
        assertNull(task.organizationId());
        assertNull(task.projectId());
        assertNull(task.snoozedUntil());
    }

    @Test
    void toTaskToleratesNullOptionalFieldsWithoutThrowing() {
        Deal deal = Deal.builder()
                .dealId("D-90002")
                .title("Sparse deal")
                .description(null)
                .stage(DealStage.PROSPECT)
                .ownerEmail(null)
                .closeDate(null)
                .dealValue(null)
                .stageChangedAt(Instant.parse("2026-08-20T00:00:00Z"))
                .build();

        Task task = DealToTaskMapper.toTask(deal, AS_OF);

        assertEquals("Sparse deal", task.title());
        assertNull(task.description());
        assertNull(task.deadline());
        assertEquals("crm:D-90002", task.sourceRef());
        assertEquals(TaskStatus.NEW, task.status());
        assertTrue(task.urgency() >= 1 && task.urgency() <= 5);
    }

    @Test
    void allFourD10432RecordsMapToTheSameSourceRef() {
        JsonDealRepository repo = repository();
        List<Deal> history = repo.findHistoryByDealId("D-10432");

        assertEquals(4, history.size());

        List<String> sourceRefs = history.stream()
                .map(DealToTaskMapper::toSourceRef)
                .distinct()
                .collect(Collectors.toList());

        assertEquals(1, sourceRefs.size());
        assertEquals("crm:D-10432", sourceRefs.get(0));
    }

    @Test
    void toUrgencyIsInRangeForEveryRecordInTheDataset() {
        JsonDealRepository repo = repository();

        for (Deal deal : repo.findAll()) {
            Integer urgency = DealToTaskMapper.toUrgency(deal, repo.asOf());
            assertTrue(urgency >= 1 && urgency <= 5,
                    () -> "urgency out of range for " + deal.dealId() + ": " + urgency);
        }
    }

    @Test
    void urgencyPinsExactValuesForTargetedStagesAndCloseDates() {
        JsonDealRepository repo = repository();

        // D-10541: NEGOTIATION closing today (2026-08-23) -> base 4 + bonus 2, clamped to 5.
        Deal negotiationClosingToday = findLatest(repo, "D-10541");
        assertEquals(DealStage.NEGOTIATION, negotiationClosingToday.stage());
        assertEquals(LocalDate.of(2026, 8, 23), negotiationClosingToday.closeDate());
        assertEquals(5, DealToTaskMapper.toUrgency(negotiationClosingToday, AS_OF));

        // D-10530: PROPOSAL closing 2027-03-31, far beyond the 14-day window -> base 3 + bonus 0.
        Deal proposalClosingFarOut = findLatest(repo, "D-10530");
        assertEquals(DealStage.PROPOSAL, proposalClosingFarOut.stage());
        assertEquals(LocalDate.of(2027, 3, 31), proposalClosingFarOut.closeDate());
        assertEquals(3, DealToTaskMapper.toUrgency(proposalClosingFarOut, AS_OF));

        // D-10520: ON_HOLD, already past its close date -> base 1, no bonus for a paused deal.
        Deal onHoldPastClose = findLatest(repo, "D-10520");
        assertEquals(DealStage.ON_HOLD, onHoldPastClose.stage());
        assertEquals(LocalDate.of(2026, 8, 5), onHoldPastClose.closeDate());
        assertEquals(1, DealToTaskMapper.toUrgency(onHoldPastClose, AS_OF));
    }

    @Test
    void closedWonWithNullCloseDateYieldsNullDeadlineAndValidUrgency() {
        JsonDealRepository repo = repository();

        Deal closedWonNoCloseDate = findLatest(repo, "D-10552");
        assertEquals(DealStage.CLOSED_WON, closedWonNoCloseDate.stage());
        assertNull(closedWonNoCloseDate.closeDate());

        Task task = DealToTaskMapper.toTask(closedWonNoCloseDate, AS_OF);

        assertNull(task.deadline());
        assertEquals(1, task.urgency());
    }

    @Test
    void toDeadlineMapsDateToEndOfDayUtcAndNullToNull() {
        LocalDate closeDate = LocalDate.of(2026, 9, 30);

        Instant deadline = DealToTaskMapper.toDeadline(closeDate);

        assertEquals(Instant.parse("2026-09-30T23:59:59.999999999Z"), deadline);
        assertNull(DealToTaskMapper.toDeadline(null));
    }

    @Test
    void toTaskRejectsNullDealAndNullAsOf() {
        Deal deal = Deal.builder().dealId("D-90003").stageChangedAt(AS_OF).build();

        assertThrows(NullPointerException.class, () -> DealToTaskMapper.toTask(null, AS_OF));
        assertThrows(NullPointerException.class, () -> DealToTaskMapper.toTask(deal, null));
    }
}
