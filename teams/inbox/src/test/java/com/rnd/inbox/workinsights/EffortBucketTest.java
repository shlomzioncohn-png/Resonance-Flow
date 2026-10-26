package com.rnd.inbox.workinsights;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EffortBucketTest {

    private static final int SHORT_MAX_MINUTES = 30;
    private static final int MEDIUM_MAX_MINUTES = 120;

    @Test
    void fromMinutes_whenZero_returnsShort() {
        assertThat(EffortBucket.fromMinutes(0, SHORT_MAX_MINUTES, MEDIUM_MAX_MINUTES)).isEqualTo(EffortBucket.SHORT);
    }

    @Test
    void fromMinutes_whenExactlyShortMax_returnsShort() {
        assertThat(EffortBucket.fromMinutes(30, SHORT_MAX_MINUTES, MEDIUM_MAX_MINUTES)).isEqualTo(EffortBucket.SHORT);
    }

    @Test
    void fromMinutes_whenJustAboveShortMax_returnsMedium() {
        assertThat(EffortBucket.fromMinutes(31, SHORT_MAX_MINUTES, MEDIUM_MAX_MINUTES)).isEqualTo(EffortBucket.MEDIUM);
    }

    @Test
    void fromMinutes_whenExactlyMediumMax_returnsMedium() {
        assertThat(EffortBucket.fromMinutes(120, SHORT_MAX_MINUTES, MEDIUM_MAX_MINUTES)).isEqualTo(EffortBucket.MEDIUM);
    }

    @Test
    void fromMinutes_whenJustAboveMediumMax_returnsLong() {
        assertThat(EffortBucket.fromMinutes(121, SHORT_MAX_MINUTES, MEDIUM_MAX_MINUTES)).isEqualTo(EffortBucket.LONG);
    }
}
