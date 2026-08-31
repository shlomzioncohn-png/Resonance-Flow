package com.rnd.bridge.crm.repository;

import com.rnd.bridge.crm.model.Deal;
import com.rnd.bridge.crm.model.DealStage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs against H2 in Postgres compatibility mode with Flyway applying the
 * real V1 migration and {@link CrmDealsSeeder} running for real at context
 * startup - the same migration and seeding path the jdbc profile uses in
 * production, just pointed at an in-memory database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.datasource.url=jdbc:h2:mem:crm_deals_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "bridge.crm.as-of=2026-08-24T09:00:00Z"
})
@ActiveProfiles("jdbc")
class JdbcDealRepositoryTest {

    @Autowired
    private JdbcDealRepository jdbcDealRepository;

    @Autowired
    private JsonDealRepository jsonDealRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CrmDealsSeeder seeder;

    @Test
    void findAllIsOrderedByStageChangedAtThenDealId() {
        List<Deal> all = jdbcDealRepository.findAll();

        for (int i = 1; i < all.size(); i++) {
            int index = i;
            Deal previous = all.get(i - 1);
            Deal current = all.get(i);
            int cmp = previous.stageChangedAt().compareTo(current.stageChangedAt());
            assertTrue(cmp < 0 || (cmp == 0 && previous.dealId().compareTo(current.dealId()) <= 0),
                    () -> "out of order at index " + index + ": " + previous + " then " + current);
        }

        // D-11010 and D-11011 deliberately share a stage_changed_at in the
        // dataset, to exercise this exact tiebreak.
        List<String> tiedPair = all.stream()
                .filter(d -> d.dealId().equals("D-11010") || d.dealId().equals("D-11011"))
                .map(Deal::dealId)
                .toList();
        assertEquals(List.of("D-11010", "D-11011"), tiedPair);
    }

    @Test
    void reopenedDealAfterClosedLostReturnsHistoryInOrderEndingOnThePostReopenStage() {
        List<Deal> history = jdbcDealRepository.findHistoryByDealId("D-11007");

        assertEquals(
                List.of(DealStage.PROSPECT, DealStage.QUALIFIED, DealStage.CLOSED_LOST, DealStage.QUALIFIED),
                history.stream().map(Deal::stage).toList());

        Deal latest = jdbcDealRepository.findLatestByDealId("D-11007").orElseThrow();
        assertEquals(DealStage.QUALIFIED, latest.stage());
        assertEquals(history.get(3), latest);
    }

    @Test
    void findAllDealIdsHasNoDuplicatesAndIsSmallerThanTotalRecords() {
        List<String> dealIds = jdbcDealRepository.findAllDealIds();

        assertEquals(dealIds.size(), dealIds.stream().distinct().count());
        assertTrue(dealIds.size() < jdbcDealRepository.findAll().size());
    }

    @Test
    void aRecordWithNullCloseDateRoundTripsAsNullNotADefault() {
        Deal deal = jdbcDealRepository.findLatestByDealId("D-10502").orElseThrow();

        assertNull(deal.closeDate());
    }

    @Test
    void asOfReturnsTheConfiguredInstant() {
        assertEquals(Instant.parse("2026-08-24T09:00:00Z"), jdbcDealRepository.asOf());
    }

    @Test
    void seederIsIdempotent() {
        Long before = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_deals", Long.class);

        seeder.run(new DefaultApplicationArguments());

        Long after = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM crm_deals", Long.class);
        assertEquals(before, after);
    }

    @Test
    void jdbcAndJsonRepositoriesAreEquivalent() {
        List<Deal> jsonAll = jsonDealRepository.findAll();
        List<Deal> jdbcAll = jdbcDealRepository.findAll();

        assertEquals(jsonAll.size(), jdbcAll.size());
        for (int i = 0; i < jsonAll.size(); i++) {
            assertDealsEquivalent(jsonAll.get(i), jdbcAll.get(i));
        }

        assertEquals(jsonDealRepository.findAllDealIds(), jdbcDealRepository.findAllDealIds());

        for (String dealId : jsonDealRepository.findAllDealIds()) {
            Optional<Deal> fromJson = jsonDealRepository.findLatestByDealId(dealId);
            Optional<Deal> fromJdbc = jdbcDealRepository.findLatestByDealId(dealId);

            assertEquals(fromJson.isPresent(), fromJdbc.isPresent());
            fromJson.ifPresent(expected -> assertDealsEquivalent(expected, fromJdbc.orElseThrow()));
        }
    }

    /**
     * {@link Deal}'s auto-generated record equals() compares dealValue via
     * BigDecimal.equals(), which is scale-sensitive. Jackson parses a whole
     * JSON number like 148000 at scale 0; the crm_deals.deal_value column is
     * NUMERIC(15,2), so JDBC always reads it back at scale 2 (148000.00).
     * Numerically equal, not .equals()-equal - not a bug in either
     * repository, just a representation difference this comparison has to
     * account for since Deal cannot be changed to fix it.
     */
    private static void assertDealsEquivalent(Deal expected, Deal actual) {
        assertEquals(expected.dealId(), actual.dealId());
        assertEquals(expected.title(), actual.title());
        assertEquals(expected.description(), actual.description());
        assertEquals(expected.stage(), actual.stage());
        assertEquals(expected.ownerEmail(), actual.ownerEmail());
        assertEquals(expected.ownerName(), actual.ownerName());
        assertEquals(expected.closeDate(), actual.closeDate());
        assertEquals(0, compareNullable(expected.dealValue(), actual.dealValue()),
                () -> "dealValue mismatch for " + expected.dealId() + ": "
                        + expected.dealValue() + " vs " + actual.dealValue());
        assertEquals(expected.currency(), actual.currency());
        assertEquals(expected.crmAccountSlug(), actual.crmAccountSlug());
        assertEquals(expected.sourceUrl(), actual.sourceUrl());
        assertEquals(expected.createdAt(), actual.createdAt());
        assertEquals(expected.stageChangedAt(), actual.stageChangedAt());
    }

    private static int compareNullable(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            assertFalse(a != b, "one of the two values is null and the other is not: " + a + " / " + b);
            return 0;
        }
        return a.compareTo(b);
    }
}
