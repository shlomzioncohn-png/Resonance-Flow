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
}
