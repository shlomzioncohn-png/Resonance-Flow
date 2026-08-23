package com.rnd.bridge.crm.mapper;

import com.rnd.bridge.crm.model.Deal;
import com.rnd.bridge.crm.model.DealStage;
import com.rnd.bridge.dto.TaskStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class DealToTaskMapperTest {

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
            "ON_HOLD,     BLOCKED",
            "CLOSED_WON,  DONE",
            "CLOSED_LOST, DONE"
    })
    void mapsEveryDealStageToExpectedTaskStatus(DealStage stage, TaskStatus expected) {
        assertEquals(expected, DealToTaskMapper.toTaskStatus(stage));
    }

    @Test
    void reopenedDealRevivesTaskStatusBackToNew() {
        Instant wonAt = Instant.parse("2026-08-20T10:00:00Z");
        Instant reopenedAt = wonAt.plus(1, ChronoUnit.DAYS);

        Deal won = new Deal("D-10432", DealStage.CLOSED_WON, null, null, null, wonAt);
        Deal reopened = new Deal("D-10432", DealStage.NEGOTIATION, null, null, null, reopenedAt);

        assertEquals(TaskStatus.DONE, DealToTaskMapper.toTaskStatus(won.stage()));
        assertEquals(TaskStatus.NEW, DealToTaskMapper.toTaskStatus(reopened.stage()));

        // Same dealId must still resolve to the same dedup key after the
        // reopen - otherwise the reopened deal creates a second, invisible
        // Task instead of reviving the existing one.
        assertEquals(DealToTaskMapper.toTaskId(won), DealToTaskMapper.toTaskId(reopened));
    }

    @Test
    void sameDealYieldsIdenticalIdOnRepeatedMapping() {
        Deal deal = new Deal("D-10432", DealStage.NEGOTIATION, null, null, null, Instant.now());

        assertEquals(DealToTaskMapper.toTaskId(deal), DealToTaskMapper.toTaskId(deal));
    }

    @Test
    void idIsDerivedFromDealIdAloneNotFromStageOrTimestampOrOwner() {
        Deal before = new Deal("D-10432", DealStage.PROPOSAL, null, null, null, Instant.now());
        Deal after = new Deal("D-10432", DealStage.CLOSED_WON, "owner@example.com", null, null,
                Instant.now().plus(1, ChronoUnit.HOURS));

        assertEquals(DealToTaskMapper.toTaskId(before), DealToTaskMapper.toTaskId(after));
    }

    @Test
    void differentDealsYieldDifferentIds() {
        Deal a = new Deal("D-10432", DealStage.PROSPECT, null, null, null, Instant.now());
        Deal b = new Deal("D-99999", DealStage.PROSPECT, null, null, null, Instant.now());

        assertNotEquals(DealToTaskMapper.toTaskId(a), DealToTaskMapper.toTaskId(b));
    }
}
