package com.rnd.inbox.seedgen.generator.task;

import com.rnd.inbox.seedgen.config.DatasetConfig;

/**
 * A task's deadline, still as a category (design doc §6) rather than an actual timestamp — a
 * real date needs a "now" to measure from, and baking one in here would break reproducibility
 * (the same seed run on different days would compute different dates). Materializing an actual
 * {@code Instant} from this category happens later, against whatever "now" the CSV-writing step
 * uses — deliberately not reproducible at that exact point, because an "overdue" task is
 * supposed to stay overdue whenever the data is loaded, not sit at a fixed date that drifts
 * further into the past with every re-run.
 *
 * @param bucket one of {@link DatasetConfig#DEADLINE}'s keys except {@link DatasetConfig#DEADLINE_NONE}
 *               ({@code null} here instead — see {@link #hasDeadline()}), e.g.
 *               {@link DatasetConfig#DEADLINE_OVERDUE}
 * @param withinHours only meaningful when {@code bucket} is {@link DatasetConfig#DEADLINE_NEXT_7_DAYS}:
 *                     {@code true} for "due in a few hours" rather than "due in a few days"
 * @param extremeOverdue only meaningful when {@code bucket} is {@link DatasetConfig#DEADLINE_OVERDUE}:
 *                        the 6+ months edge case (design doc §6), rather than recently past due
 */
public record DeadlineSeed(String bucket, boolean withinHours, boolean extremeOverdue) {

    public static final DeadlineSeed NONE = new DeadlineSeed(null, false, false);

    public boolean hasDeadline() {
        return bucket != null;
    }
}
