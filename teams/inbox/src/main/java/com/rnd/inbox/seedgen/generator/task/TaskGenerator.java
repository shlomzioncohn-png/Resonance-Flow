package com.rnd.inbox.seedgen.generator.task;
import com.rnd.inbox.seedgen.generator.shared.RandomUtil;

import com.rnd.inbox.seedgen.config.DatasetConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Builds every task's field values (design doc §6) — global draws, independent of which tenant
 * will eventually own the task. Each of the {@link DatasetConfig#TOTAL_TASKS} tasks is an
 * independent per-task draw (§6's documented approach, as opposed to the exact-count-then-shuffle
 * technique {@link TenantGenerator} uses): the actual share of e.g. {@code done} tasks converges
 * close to its target percentage but not onto it exactly — still fully reproducible, since the
 * same seed draws the same sequence of values every run.
 */
public final class TaskGenerator {

    private TaskGenerator() {
    }

    /** Generates {@code count} tasks, each an independent draw from the same {@link Random}. */
    public static List<TaskSeed> generate(int count, Random random) {
        List<TaskSeed> tasks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            tasks.add(generateOne(random));
        }
        return List.copyOf(tasks);
    }

    private static TaskSeed generateOne(Random random) {
        boolean minimalRow = random.nextDouble() < DatasetConfig.MINIMAL_ROW_SHARE;

        String status = RandomUtil.pick(random, DatasetConfig.STATUS);
        String sourceType = RandomUtil.pick(random, DatasetConfig.SOURCE_TYPE);
        String extractionProvenance = RandomUtil.pick(random, DatasetConfig.EXTRACTION_PROVENANCE);
        Double confidence = drawConfidence(random, extractionProvenance);

        String tier = minimalRow ? null : nullIfSentinel(RandomUtil.pick(random, DatasetConfig.TIER));
        String roleContext = minimalRow ? null : nullIfSentinel(RandomUtil.pick(random, DatasetConfig.ROLE_CONTEXT));
        DeadlineSeed deadline = minimalRow ? DeadlineSeed.NONE : drawDeadline(random);
        boolean isAvailable = drawIsAvailable(random, status);
        Integer priorityScore = minimalRow ? null : drawPriorityScore(random);
        Integer urgency = minimalRow ? null : drawUrgency(random);

        Integer effortEstimateMinutes = null;
        double effortEstimateConfidence;
        if (random.nextDouble() < DatasetConfig.EFFORT_ESTIMATE_PRESENT_SHARE) {
            effortEstimateMinutes = (int) Math.round(RandomUtil.inRange(random, DatasetConfig.EFFORT_ESTIMATE_MINUTES));
            effortEstimateConfidence = RandomUtil.inRange(random, DatasetConfig.EFFORT_ESTIMATE_CONFIDENCE_WHEN_PRESENT);
        } else {
            effortEstimateConfidence = RandomUtil.inRange(random, DatasetConfig.EFFORT_ESTIMATE_CONFIDENCE_WHEN_ABSENT);
        }

        return new TaskSeed(status, sourceType, extractionProvenance, confidence, tier, roleContext,
                deadline, isAvailable, priorityScore, urgency, effortEstimateMinutes, effortEstimateConfidence);
    }

    /** Only {@code ml_inferred} tasks carry a confidence — required by the schema; {@code etl}/
     * {@code manual} never do (design doc §6). A tail within {@code ml_inferred} skews toward a
     * near-guess confidence instead of the usual higher-confidence range. */
    private static Double drawConfidence(Random random, String extractionProvenance) {
        if (!extractionProvenance.equals("ml_inferred")) {
            return null;
        }
        boolean lowConfidenceTail = random.nextDouble() < DatasetConfig.ML_INFERRED_LOW_CONFIDENCE_TAIL_SHARE;
        return lowConfidenceTail
                ? RandomUtil.inRange(random, DatasetConfig.ML_INFERRED_LOW_CONFIDENCE_TAIL)
                : RandomUtil.inRange(random, DatasetConfig.ML_INFERRED_CONFIDENCE);
    }

    private static DeadlineSeed drawDeadline(Random random) {
        String bucket = RandomUtil.pick(random, DatasetConfig.DEADLINE);
        if (bucket.equals(DatasetConfig.DEADLINE_NONE)) {
            return DeadlineSeed.NONE;
        }
        boolean withinHours = bucket.equals(DatasetConfig.DEADLINE_NEXT_7_DAYS)
                && random.nextDouble() < DatasetConfig.DEADLINE_NEXT_7_DAYS_WITHIN_HOURS_SHARE;
        boolean extremeOverdue = bucket.equals(DatasetConfig.DEADLINE_OVERDUE)
                && random.nextDouble() < DatasetConfig.DEADLINE_EXTREME_OVERDUE_SHARE;
        return new DeadlineSeed(bucket, withinHours, extremeOverdue);
    }

    /** Closed statuses are never available (design doc §6, a real logical consequence of the
     * status, not a fabricated correlation); open statuses follow the documented 75/25 split. */
    private static boolean drawIsAvailable(Random random, String status) {
        if (DatasetConfig.CLOSED_STATUSES.contains(status)) {
            return false;
        }
        return random.nextDouble() < DatasetConfig.OPEN_STATUS_AVAILABLE_SHARE;
    }

    private static Integer drawPriorityScore(Random random) {
        if (random.nextDouble() >= DatasetConfig.PRIORITY_SCORE_PRESENT_SHARE) {
            return null;
        }
        if (random.nextDouble() < DatasetConfig.PRIORITY_SCORE_BOUNDARY_TAIL_SHARE) {
            return random.nextBoolean() ? DatasetConfig.PRIORITY_SCORE_RANGE.min() : DatasetConfig.PRIORITY_SCORE_RANGE.max();
        }
        return RandomUtil.intSkewedMid(random, DatasetConfig.PRIORITY_SCORE_RANGE);
    }

    private static Integer drawUrgency(Random random) {
        if (random.nextDouble() >= DatasetConfig.URGENCY_PRESENT_SHARE) {
            return null;
        }
        return RandomUtil.intSkewedMid(random, DatasetConfig.URGENCY_RANGE);
    }

    private static String nullIfSentinel(String value) {
        return value.equals(DatasetConfig.NULL_KEY) ? null : value;
    }
}
