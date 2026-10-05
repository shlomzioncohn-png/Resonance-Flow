package com.rnd.inbox.seedgen;

import com.rnd.inbox.seedgen.config.DatasetConfig;
import com.rnd.inbox.seedgen.generator.task.DeadlineSeed;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class DeadlineMaterializerTest {

    private static final Instant NOW = Instant.parse("2026-09-15T12:00:00Z");

    @Test
    void materialize_none_returnsNull() {
        assertThat(DeadlineMaterializer.materialize(DeadlineSeed.NONE, NOW, new Random(1))).isNull();
    }

    @Test
    void materialize_recentOverdue_isBeforeNowWithinRange() {
        DeadlineSeed deadline = new DeadlineSeed(DatasetConfig.DEADLINE_OVERDUE, false, false);

        for (int seed = 0; seed < 100; seed++) {
            Instant result = DeadlineMaterializer.materialize(deadline, NOW, new Random(seed));
            Duration ago = Duration.between(result, NOW);
            assertThat(ago).isBetween(Duration.ofDays(DatasetConfig.DEADLINE_RECENT_OVERDUE_DAYS_AGO.min()),
                    Duration.ofDays(DatasetConfig.DEADLINE_RECENT_OVERDUE_DAYS_AGO.max()));
        }
    }

    @Test
    void materialize_extremeOverdue_isBeforeNowWithinRange() {
        DeadlineSeed deadline = new DeadlineSeed(DatasetConfig.DEADLINE_OVERDUE, false, true);

        Instant result = DeadlineMaterializer.materialize(deadline, NOW, new Random(1));

        Duration ago = Duration.between(result, NOW);
        assertThat(ago).isBetween(Duration.ofDays(DatasetConfig.DEADLINE_EXTREME_OVERDUE_DAYS_AGO.min()),
                Duration.ofDays(DatasetConfig.DEADLINE_EXTREME_OVERDUE_DAYS_AGO.max()));
    }

    @Test
    void materialize_next7DaysWithinHours_isAfterNowWithinHourRange() {
        DeadlineSeed deadline = new DeadlineSeed(DatasetConfig.DEADLINE_NEXT_7_DAYS, true, false);

        Instant result = DeadlineMaterializer.materialize(deadline, NOW, new Random(1));

        Duration ahead = Duration.between(NOW, result);
        assertThat(ahead).isBetween(Duration.ofHours(DatasetConfig.DEADLINE_WITHIN_HOURS_FROM_NOW.min()),
                Duration.ofHours(DatasetConfig.DEADLINE_WITHIN_HOURS_FROM_NOW.max()));
    }

    @Test
    void materialize_next7DaysNotWithinHours_isAfterNowWithinDayRange() {
        DeadlineSeed deadline = new DeadlineSeed(DatasetConfig.DEADLINE_NEXT_7_DAYS, false, false);

        Instant result = DeadlineMaterializer.materialize(deadline, NOW, new Random(1));

        Duration ahead = Duration.between(NOW, result);
        assertThat(ahead).isBetween(Duration.ofDays(DatasetConfig.DEADLINE_WITHIN_7_DAYS_FROM_NOW.min()),
                Duration.ofDays(DatasetConfig.DEADLINE_WITHIN_7_DAYS_FROM_NOW.max()));
    }

    @Test
    void materialize_next30Days_isAfterNowWithinRange() {
        DeadlineSeed deadline = new DeadlineSeed(DatasetConfig.DEADLINE_NEXT_30_DAYS, false, false);

        Instant result = DeadlineMaterializer.materialize(deadline, NOW, new Random(1));

        Duration ahead = Duration.between(NOW, result);
        assertThat(ahead).isBetween(Duration.ofDays(DatasetConfig.DEADLINE_WITHIN_30_DAYS_FROM_NOW.min()),
                Duration.ofDays(DatasetConfig.DEADLINE_WITHIN_30_DAYS_FROM_NOW.max()));
    }

    @Test
    void materialize_furtherOut_isAfterNowWithinRange() {
        DeadlineSeed deadline = new DeadlineSeed(DatasetConfig.DEADLINE_FURTHER_OUT, false, false);

        Instant result = DeadlineMaterializer.materialize(deadline, NOW, new Random(1));

        Duration ahead = Duration.between(NOW, result);
        assertThat(ahead).isBetween(Duration.ofDays(DatasetConfig.DEADLINE_FURTHER_OUT_DAYS_FROM_NOW.min()),
                Duration.ofDays(DatasetConfig.DEADLINE_FURTHER_OUT_DAYS_FROM_NOW.max()));
    }

    @Test
    void materialize_sameInputs_alwaysProduceTheSameInstant() {
        DeadlineSeed deadline = new DeadlineSeed(DatasetConfig.DEADLINE_NEXT_30_DAYS, false, false);

        Instant first = DeadlineMaterializer.materialize(deadline, NOW, new Random(42));
        Instant second = DeadlineMaterializer.materialize(deadline, NOW, new Random(42));

        assertThat(first).isEqualTo(second);
    }
}
