package com.rnd.bridge.crm.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
}
