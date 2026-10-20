package com.rnd.bridge.crm.mapper;

import com.rnd.bridge.crm.model.Deal;
import com.rnd.bridge.crm.model.DealStage;
import com.rnd.bridge.crm.repository.DealSnapshot;
import com.rnd.bridge.crm.repository.JsonDealRepository;
import com.rnd.bridge.task.enrichment.EnrichmentProperties;
import com.rnd.bridge.task.enrichment.NoOpTaskEnricher;
import com.rnd.bridge.task.model.ExtractionProvenance;
import com.rnd.bridge.task.model.RoleContext;
import com.rnd.bridge.task.model.SourceType;
import com.rnd.bridge.task.model.Task;
import com.rnd.bridge.task.model.TaskStatus;
import com.rnd.bridge.task.model.Tier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.DefaultResourceLoader;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
    private static final String ORGANIZATION_ID = "org-test-0000";
    // Pre-existing tests don't exercise the stalled-deal bump, so the mapper under test here keeps
    // it disabled (stalledAfterDays = 0) to avoid changing any of their pinned urgency values.
    private static final DealToTaskMapper MAPPER = new DealToTaskMapper(ORGANIZATION_ID, new EnrichmentProperties(), new NoOpTaskEnricher(), 0);

    private static JsonDealRepository repository() {
        return new JsonDealRepository(new DefaultResourceLoader(), "classpath:crm/synthetic-deals.json");
    }

    private static Deal findLatest(JsonDealRepository repo, String dealId) {
        return repo.snapshot().findLatestByDealId(dealId).orElseThrow();
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
            "ON_HOLD,     NEW",
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
        assertEquals(MAPPER.toSourceRef(won), MAPPER.toSourceRef(reopened));
    }

    @Test
    void sameDealYieldsIdenticalSourceRefOnRepeatedMapping() {
        Deal deal = Deal.builder().dealId("D-10432").stage(DealStage.NEGOTIATION).stageChangedAt(Instant.now()).build();

        assertEquals(MAPPER.toSourceRef(deal), MAPPER.toSourceRef(deal));
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

        assertEquals(MAPPER.toSourceRef(before), MAPPER.toSourceRef(after));
    }

    @Test
    void differentDealsYieldDifferentSourceRefs() {
        Deal a = Deal.builder().dealId("D-10432").stage(DealStage.PROSPECT).stageChangedAt(Instant.now()).build();
        Deal b = Deal.builder().dealId("D-99999").stage(DealStage.PROSPECT).stageChangedAt(Instant.now()).build();

        assertNotEquals(MAPPER.toSourceRef(a), MAPPER.toSourceRef(b));
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

        Task task = MAPPER.map(deal, AS_OF);

        assertEquals("Full deal title", task.title());
        assertEquals("Full deal description", task.description());
        assertEquals(SourceType.CRM_DEAL, task.sourceType());
        assertEquals("crm:D-90001", task.sourceRef());
        assertEquals(RoleContext.SALESPERSON, task.roleContext());
        assertEquals(Tier.TACTICAL, task.tier());
        assertEquals(TaskStatus.NEW, task.status());
        assertEquals(5, task.urgency()); // NEGOTIATION base 4 + close-in-3-days bonus 2, clamped to 5
        assertEquals(
                LocalDate.of(2026, 8, 26).atTime(23, 59, 59).toInstant(ZoneOffset.UTC),
                task.deadline());
        assertEquals(ExtractionProvenance.ETL, task.extractionProvenance());
        assertNull(task.confidence());
        assertNull(task.effortEstimateMinutes());
        assertEquals(ORGANIZATION_ID, task.organizationId());
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

        Task task = MAPPER.map(deal, AS_OF);

        assertEquals("Sparse deal", task.title());
        assertNull(task.description());
        assertNull(task.deadline());
        assertEquals("crm:D-90002", task.sourceRef());
        assertEquals(TaskStatus.NEW, task.status());
        assertTrue(task.urgency() >= 1 && task.urgency() <= 5);
    }

    @Test
    void jsonSourceDealsStayExactlyCrmPrefixedBareId() {
        // JsonDealRepository never sets Deal.sourceRefPrefix, so a Deal built with no explicit
        // override (same shape JsonDealRepository produces) keeps the builder's own bare "crm:"
        // default - HubSpot and Salesforce both now set an explicit prefix of their own (see
        // hubSpotSourceDealsGetTheExplicitHubspotPrefix below). Pinning this exact string (not just
        // "starts with crm:") catches a regression where the default drifts or doubles up (e.g.
        // "crm:crm:D-1").
        Deal deal = Deal.builder().dealId("D-1").stageChangedAt(Instant.now()).build();

        assertEquals("crm:", deal.sourceRefPrefix());
        assertEquals("crm:D-1", MAPPER.toSourceRef(deal));
    }

    @Test
    void hubSpotSourceDealsGetTheExplicitHubspotPrefix() {
        // HubSpotDealRepository sets sourceRefPrefix explicitly to "crm:hubspot:" (distinct from
        // JSON's bare "crm:" and Salesforce's "crm:salesforce:") so a HubSpot-sourced task's
        // sourceRef is never confused with either once more than one CRM source is active at once.
        Deal deal = Deal.builder()
                .dealId("D-1")
                .sourceRefPrefix("crm:hubspot:")
                .stageChangedAt(Instant.now())
                .build();

        assertEquals("crm:hubspot:", deal.sourceRefPrefix());
        assertEquals("crm:hubspot:D-1", MAPPER.toSourceRef(deal));
    }

    @Test
    void allFourD10432RecordsMapToTheSameSourceRef() {
        JsonDealRepository repo = repository();
        List<Deal> history = repo.snapshot().findHistoryByDealId("D-10432");

        assertEquals(4, history.size());

        List<String> sourceRefs = history.stream()
                .map(MAPPER::toSourceRef)
                .distinct()
                .collect(Collectors.toList());

        assertEquals(1, sourceRefs.size());
        assertEquals("crm:D-10432", sourceRefs.get(0));
    }

    @Test
    void toUrgencyIsInRangeForEveryRecordInTheDataset() {
        JsonDealRepository repo = repository();
        DealSnapshot snapshot = repo.snapshot();

        for (Deal deal : snapshot.allDeals()) {
            Integer urgency = MAPPER.toUrgency(deal, snapshot.asOf());
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
        assertEquals(5, MAPPER.toUrgency(negotiationClosingToday, AS_OF));

        // D-10530: PROPOSAL closing 2027-03-31, far beyond the 14-day window -> base 3 + bonus 0.
        Deal proposalClosingFarOut = findLatest(repo, "D-10530");
        assertEquals(DealStage.PROPOSAL, proposalClosingFarOut.stage());
        assertEquals(LocalDate.of(2027, 3, 31), proposalClosingFarOut.closeDate());
        assertEquals(3, MAPPER.toUrgency(proposalClosingFarOut, AS_OF));

        // D-10520: ON_HOLD, already past its close date -> base 1, no bonus for a paused deal.
        Deal onHoldPastClose = findLatest(repo, "D-10520");
        assertEquals(DealStage.ON_HOLD, onHoldPastClose.stage());
        assertEquals(LocalDate.of(2026, 8, 5), onHoldPastClose.closeDate());
        assertEquals(1, MAPPER.toUrgency(onHoldPastClose, AS_OF));
    }

    @Test
    void closedWonWithNullCloseDateYieldsNullDeadlineAndValidUrgency() {
        JsonDealRepository repo = repository();

        Deal closedWonNoCloseDate = findLatest(repo, "D-10552");
        assertEquals(DealStage.CLOSED_WON, closedWonNoCloseDate.stage());
        assertNull(closedWonNoCloseDate.closeDate());

        Task task = MAPPER.map(closedWonNoCloseDate, AS_OF);

        assertNull(task.deadline());
        assertEquals(1, task.urgency());
        // A CLOSED_WON deal with no close date recorded is still DONE - the
        // rep marked it won - but there is nothing to derive completedAt
        // from, so it stays null rather than throwing or defaulting.
        assertEquals(TaskStatus.DONE, task.status());
        assertNull(task.completedAt());
    }

    @Test
    void closedWonSetsCompletedAtFromTheCloseDate() {
        Deal deal = Deal.builder()
                .dealId("D-90010")
                .title("Won deal")
                .stage(DealStage.CLOSED_WON)
                .closeDate(LocalDate.of(2026, 8, 8))
                .stageChangedAt(Instant.parse("2026-08-08T16:00:00Z"))
                .build();

        Task task = MAPPER.map(deal, AS_OF);

        assertEquals(TaskStatus.DONE, task.status());
        assertEquals(
                LocalDate.of(2026, 8, 8).atTime(23, 59, 59).toInstant(ZoneOffset.UTC),
                task.completedAt());
        // Reuses toDeadline's conversion, so the two cannot drift.
        assertEquals(task.deadline(), task.completedAt());
    }

    @Test
    void closedLostLeavesCompletedAtNull() {
        Deal deal = Deal.builder()
                .dealId("D-90011")
                .title("Lost deal")
                .stage(DealStage.CLOSED_LOST)
                .closeDate(LocalDate.of(2026, 8, 8))
                .stageChangedAt(Instant.parse("2026-08-08T16:00:00Z"))
                .build();

        Task task = MAPPER.map(deal, AS_OF);

        assertEquals(TaskStatus.CANCELLED, task.status());
        assertNull(task.completedAt());
    }

    @Test
    void anOpenStageLeavesCompletedAtNull() {
        Deal deal = Deal.builder()
                .dealId("D-90012")
                .title("Still open")
                .stage(DealStage.NEGOTIATION)
                .closeDate(LocalDate.of(2026, 9, 1))
                .stageChangedAt(Instant.parse("2026-08-20T00:00:00Z"))
                .build();

        Task task = MAPPER.map(deal, AS_OF);

        assertNull(task.completedAt());
    }

    @Test
    void toDeadlineMapsDateToEndOfDayUtcAndNullToNull() {
        LocalDate closeDate = LocalDate.of(2026, 9, 30);

        Instant deadline = DealToTaskMapper.toDeadline(closeDate);

        assertEquals(Instant.parse("2026-09-30T23:59:59Z"), deadline);
        assertNull(DealToTaskMapper.toDeadline((LocalDate) null));
    }

    @Test
    void toTaskRejectsNullDealAndNullAsOf() {
        Deal deal = Deal.builder().dealId("D-90003").stageChangedAt(AS_OF).build();

        assertThrows(NullPointerException.class, () -> MAPPER.map(null, AS_OF));
        assertThrows(NullPointerException.class, () -> MAPPER.map(deal, null));
    }

    @Test
    void onHoldMapsToNewNotSnoozedAndNeverSetsSnoozedUntil() {
        Deal deal = Deal.builder()
                .dealId("D-90004")
                .title("Paused deal")
                .stage(DealStage.ON_HOLD)
                .closeDate(LocalDate.of(2026, 9, 1))
                .stageChangedAt(Instant.parse("2026-08-20T00:00:00Z"))
                .build();

        Task task = MAPPER.map(deal, AS_OF);

        assertEquals(TaskStatus.NEW, task.status());
        assertNull(task.snoozedUntil());
    }

    @Test
    void onHoldProducesUrgencyOne() {
        Deal deal = Deal.builder()
                .dealId("D-90005")
                .title("Paused deal, close date very soon")
                .stage(DealStage.ON_HOLD)
                .closeDate(AS_OF.atZone(ZoneOffset.UTC).toLocalDate()) // would earn max bonus if it were an open stage
                .stageChangedAt(Instant.parse("2026-08-20T00:00:00Z"))
                .build();

        assertEquals(1, MAPPER.toUrgency(deal, AS_OF));
    }

    @Test
    void closedLostMapsToCancelled() {
        Deal deal = Deal.builder()
                .dealId("D-90006")
                .title("Lost deal")
                .stage(DealStage.CLOSED_LOST)
                .closeDate(LocalDate.of(2026, 8, 1))
                .stageChangedAt(Instant.parse("2026-08-01T00:00:00Z"))
                .build();

        Task task = MAPPER.map(deal, AS_OF);

        assertEquals(TaskStatus.CANCELLED, task.status());
    }

    @Test
    void urgencyStaysClampedAtFiveWhenBonusWouldPushHigher() {
        Deal deal = Deal.builder()
                .dealId("D-90007")
                .title("Negotiation closing today")
                .stage(DealStage.NEGOTIATION) // base 4
                .closeDate(AS_OF.atZone(ZoneOffset.UTC).toLocalDate()) // 0 days out -> +2 bonus, would be 6
                .stageChangedAt(Instant.parse("2026-08-20T00:00:00Z"))
                .build();

        assertEquals(5, MAPPER.toUrgency(deal, AS_OF));
    }

    @Test
    void nullCloseDateProducesNoDeadlineAndDoesNotThrow() {
        Deal deal = Deal.builder()
                .dealId("D-90008")
                .title("No close date")
                .stage(DealStage.QUALIFIED)
                .closeDate(null)
                .stageChangedAt(Instant.parse("2026-08-20T00:00:00Z"))
                .build();

        Task task = MAPPER.map(deal, AS_OF);

        assertNull(task.deadline());
    }

    @Test
    void dayBoundaryCaseUsesAsOfDateNotAsOfTimeOfDay() {
        // Deal closes "today" per the pinned as_of date, but as_of itself is
        // well after midnight - daysToClose must still resolve to 0 (date
        // arithmetic, not instant arithmetic), giving the max bonus.
        Instant asOfAfterMidnight = Instant.parse("2026-08-23T15:45:00Z");
        Deal deal = Deal.builder()
                .dealId("D-90009")
                .title("Closing today, evaluated later in the day")
                .stage(DealStage.NEGOTIATION) // base 4
                .closeDate(LocalDate.of(2026, 8, 23))
                .stageChangedAt(Instant.parse("2026-08-20T00:00:00Z"))
                .build();

        assertEquals(5, MAPPER.toUrgency(deal, asOfAfterMidnight)); // 4 + 2, clamped to 5
    }

    // --- Stalled-deal urgency bump --------------------------------------------------------

    private static final int STALLED_AFTER_DAYS = 14;
    private static final DealToTaskMapper STALL_AWARE_MAPPER =
            new DealToTaskMapper(ORGANIZATION_ID, new EnrichmentProperties(), new NoOpTaskEnricher(), STALLED_AFTER_DAYS);

    @Test
    void oneDayShortOfTheStalledThresholdDoesNotBump() {
        Deal deal = Deal.builder()
                .dealId("D-91001")
                .stage(DealStage.PROSPECT) // base 1, no close date -> no time-pressure bonus
                .closeDate(null)
                .stageChangedAt(AS_OF.minus(STALLED_AFTER_DAYS - 1, ChronoUnit.DAYS))
                .build();

        assertEquals(1, STALL_AWARE_MAPPER.toUrgency(deal, AS_OF));
    }

    @Test
    void exactlyTheStalledThresholdBumps() {
        Deal deal = Deal.builder()
                .dealId("D-91002")
                .stage(DealStage.PROSPECT)
                .closeDate(null)
                .stageChangedAt(AS_OF.minus(STALLED_AFTER_DAYS, ChronoUnit.DAYS))
                .build();

        assertEquals(2, STALL_AWARE_MAPPER.toUrgency(deal, AS_OF));
    }

    @Test
    void wellPastTheStalledThresholdBumps() {
        Deal deal = Deal.builder()
                .dealId("D-91003")
                .stage(DealStage.QUALIFIED) // base 2
                .closeDate(null)
                .stageChangedAt(AS_OF.minus(STALLED_AFTER_DAYS * 5L, ChronoUnit.DAYS))
                .build();

        assertEquals(3, STALL_AWARE_MAPPER.toUrgency(deal, AS_OF));
    }

    @ParameterizedTest(name = "{0} stalled long stays at urgency 1")
    @CsvSource({"ON_HOLD", "CLOSED_WON", "CLOSED_LOST"})
    void onHoldAndClosedStagesStayAtUrgencyOneEvenWhenStalledLong(DealStage stage) {
        Deal deal = Deal.builder()
                .dealId("D-91004")
                .stage(stage)
                .closeDate(null)
                .stageChangedAt(AS_OF.minus(STALLED_AFTER_DAYS * 10L, ChronoUnit.DAYS))
                .build();

        assertEquals(1, STALL_AWARE_MAPPER.toUrgency(deal, AS_OF));
    }

    @Test
    void stalledNegotiationClosingSoonStaysClampedAtFive() {
        Deal deal = Deal.builder()
                .dealId("D-91005")
                .stage(DealStage.NEGOTIATION) // base 4
                .closeDate(AS_OF.atZone(ZoneOffset.UTC).toLocalDate().plusDays(2)) // +2 bonus -> 6 before the stalled +1
                .stageChangedAt(AS_OF.minus(STALLED_AFTER_DAYS * 2L, ChronoUnit.DAYS))
                .build();

        assertEquals(5, STALL_AWARE_MAPPER.toUrgency(deal, AS_OF));
    }

    @Test
    void stalledAfterDaysOfZeroDisablesTheBump() {
        DealToTaskMapper disabledMapper =
                new DealToTaskMapper(ORGANIZATION_ID, new EnrichmentProperties(), new NoOpTaskEnricher(), 0);
        Deal deal = Deal.builder()
                .dealId("D-91006")
                .stage(DealStage.PROSPECT)
                .closeDate(null)
                .stageChangedAt(AS_OF.minus(STALLED_AFTER_DAYS * 10L, ChronoUnit.DAYS))
                .build();

        assertEquals(1, disabledMapper.toUrgency(deal, AS_OF));
    }

    @Test
    void stageChangedAtAfterAsOfIsNeverStalled() {
        Deal deal = Deal.builder()
                .dealId("D-91007")
                .stage(DealStage.PROSPECT)
                .closeDate(null)
                .stageChangedAt(AS_OF.plus(5, ChronoUnit.DAYS)) // clock skew / bad data, still after AS_OF
                .build();

        assertEquals(1, STALL_AWARE_MAPPER.toUrgency(deal, AS_OF));
    }
}
