package com.rnd.inbox.seedgen;

import com.rnd.inbox.seedgen.config.DatasetConfig;
import com.rnd.inbox.seedgen.config.DatasetConfig.IntRange;
import com.rnd.inbox.seedgen.generator.task.DeadlineSeed;
import com.rnd.inbox.seedgen.generator.shared.RandomUtil;

import java.time.Duration;
import java.time.Instant;
import java.util.Random;

/**
 * Turns a {@link DeadlineSeed}'s category into a real {@link Instant} — the step
 * {@link DeadlineSeed}'s own Javadoc calls out as deliberately outside the reproducibility
 * contract: it needs an actual "now", so callers should pass a fresh, real {@link Instant#now()}
 * and a {@code Random} that is <em>not</em> {@link com.rnd.inbox.seedgen.SeedGenerator}'s shared
 * one (materialization runs after that pipeline has already finished; reusing its {@code Random}
 * here would serve no purpose and only invites confusion about which stage "owns" it).
 */
public final class DeadlineMaterializer {

    private DeadlineMaterializer() {
    }

    /** {@code null} for {@link DeadlineSeed#NONE} — matches a {@code NULL} {@code deadline} column. */
    public static Instant materialize(DeadlineSeed deadline, Instant now, Random random) {
        if (!deadline.hasDeadline()) {
            return null;
        }

        return switch (deadline.bucket()) {
            case DatasetConfig.DEADLINE_OVERDUE -> {
                IntRange daysAgo = deadline.extremeOverdue()
                        ? DatasetConfig.DEADLINE_EXTREME_OVERDUE_DAYS_AGO
                        : DatasetConfig.DEADLINE_RECENT_OVERDUE_DAYS_AGO;
                yield now.minus(randomDays(random, daysAgo));
            }
            case DatasetConfig.DEADLINE_NEXT_7_DAYS -> {
                if (deadline.withinHours()) {
                    long minutes = Math.round(RandomUtil.inRange(random, DatasetConfig.DEADLINE_WITHIN_HOURS_FROM_NOW) * 60);
                    yield now.plus(Duration.ofMinutes(minutes));
                }
                yield now.plus(randomDays(random, DatasetConfig.DEADLINE_WITHIN_7_DAYS_FROM_NOW));
            }
            case DatasetConfig.DEADLINE_NEXT_30_DAYS ->
                    now.plus(randomDays(random, DatasetConfig.DEADLINE_WITHIN_30_DAYS_FROM_NOW));
            case DatasetConfig.DEADLINE_FURTHER_OUT ->
                    now.plus(randomDays(random, DatasetConfig.DEADLINE_FURTHER_OUT_DAYS_FROM_NOW));
            default -> throw new IllegalArgumentException("Unknown deadline bucket: " + deadline.bucket());
        };
    }

    /** A random whole number of days within {@code range}, as a {@link Duration}. */
    private static Duration randomDays(Random random, IntRange range) {
        long days = Math.round(RandomUtil.inRange(random, range));
        return Duration.ofDays(days);
    }
}
