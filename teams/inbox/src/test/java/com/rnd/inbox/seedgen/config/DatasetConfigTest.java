package com.rnd.inbox.seedgen.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DatasetConfigTest {

    // RandomUtil.pick walks a table in iteration order, so an order that changes between JVM runs
    // (as an immutable Map.copyOf does) makes the same seed draw different values every run.
    @Test
    void status_always_keepsDeclarationOrder() {
        assertThat(DatasetConfig.STATUS.keySet()).containsExactly(
                "done", "in_progress", "new", "approved", "in_review", "snoozed", "rejected", "cancelled");
    }
}
