package com.rnd.bridge.crm.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DealTest {

    @Test
    void rejectsNullDealId() {
        assertThrows(NullPointerException.class,
                () -> Deal.builder().dealId(null).build());
    }

    @Test
    void rejectsNullStage() {
        assertThrows(NullPointerException.class,
                () -> Deal.builder().stage(null).build());
    }

    @Test
    void rejectsNullStageChangedAt() {
        assertThrows(NullPointerException.class,
                () -> Deal.builder().stageChangedAt(null).build());
    }

    @Test
    void rejectsBlankDealId() {
        assertThrows(IllegalArgumentException.class,
                () -> Deal.builder().dealId("   ").build());
    }

    @Test
    void acceptsDealWithOnlyTheRequiredFields() {
        assertDoesNotThrow(() -> Deal.builder().build());
    }

    @Test
    void sourceRefPrefixDefaultsToCrmWhenNotSetOnTheBuilder() {
        assertEquals("crm:", Deal.builder().build().sourceRefPrefix());
    }

    @Test
    void sourceRefPrefixDefaultsToCrmWhenNullIsPassedToTheCanonicalConstructor() {
        // The path a deserializer (e.g. JsonDealRepository's Jackson mapper) takes when the
        // source JSON has no such field - it calls the canonical constructor directly with null,
        // never the builder.
        Deal deal = new Deal("D-1", null, "Title", null, DealStage.PROSPECT, null, null, null, null,
                null, null, null, null, Instant.parse("2026-01-01T00:00:00Z"));

        assertEquals("crm:", deal.sourceRefPrefix());
    }

    @Test
    void sourceRefPrefixCanBeOverriddenOnTheBuilder() {
        assertEquals("crm:salesforce:",
                Deal.builder().sourceRefPrefix("crm:salesforce:").build().sourceRefPrefix());
    }
}
