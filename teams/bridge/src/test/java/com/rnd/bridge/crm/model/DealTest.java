package com.rnd.bridge.crm.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DealTest {

    @Test
    void rejectsNullDealId() {
        assertThrows(NullPointerException.class,
                () -> new Deal(null, DealStage.PROSPECT, null, null, null, Instant.now()));
    }

    @Test
    void rejectsNullStage() {
        assertThrows(NullPointerException.class,
                () -> new Deal("D-10432", null, null, null, null, Instant.now()));
    }

    @Test
    void rejectsBlankDealId() {
        assertThrows(IllegalArgumentException.class,
                () -> new Deal("   ", DealStage.PROSPECT, null, null, null, Instant.now()));
    }

    @Test
    void acceptsDealWithOnlyTheRequiredFields() {
        assertDoesNotThrow(() -> new Deal("D-10432", DealStage.PROSPECT, null, null, null, null));
    }
}
