package com.rnd.inbox.seedgen.generator.task;

import com.rnd.inbox.seedgen.config.DatasetConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class TaskGeneratorTest {

    private static final int SAMPLE_SIZE = 50_000;

    @Test
    void generate_always_returnsExactlyTheRequestedCount() {
        List<TaskSeed> tasks = TaskGenerator.generate(1234, new Random(DatasetConfig.SEED));

        assertThat(tasks).hasSize(1234);
    }

    @Test
    void generate_always_everyStatusAndSourceTypeIsAKnownKey() {
        List<TaskSeed> tasks = TaskGenerator.generate(SAMPLE_SIZE, new Random(DatasetConfig.SEED));

        for (TaskSeed task : tasks) {
            assertThat(DatasetConfig.STATUS).containsKey(task.status());
            assertThat(DatasetConfig.SOURCE_TYPE).containsKey(task.sourceType());
            assertThat(DatasetConfig.EXTRACTION_PROVENANCE).containsKey(task.extractionProvenance());
        }
    }

    @Test
    void generate_always_confidencePresentIffMlInferred() {
        List<TaskSeed> tasks = TaskGenerator.generate(SAMPLE_SIZE, new Random(DatasetConfig.SEED));

        for (TaskSeed task : tasks) {
            if (task.extractionProvenance().equals("ml_inferred")) {
                assertThat(task.confidence()).isNotNull();
            } else {
                assertThat(task.confidence()).isNull();
            }
        }
    }

    @Test
    void generate_always_closedStatusIsNeverAvailable() {
        List<TaskSeed> tasks = TaskGenerator.generate(SAMPLE_SIZE, new Random(DatasetConfig.SEED));

        for (TaskSeed task : tasks) {
            if (DatasetConfig.CLOSED_STATUSES.contains(task.status())) {
                assertThat(task.isAvailable()).isFalse();
            }
        }
    }

    @Test
    void generate_always_openStatusHasBothAvailableAndBlockedTasks() {
        List<TaskSeed> tasks = TaskGenerator.generate(SAMPLE_SIZE, new Random(DatasetConfig.SEED));

        boolean sawAvailable = false;
        boolean sawBlocked = false;
        for (TaskSeed task : tasks) {
            if (!DatasetConfig.CLOSED_STATUSES.contains(task.status())) {
                if (task.isAvailable()) {
                    sawAvailable = true;
                } else {
                    sawBlocked = true;
                }
            }
        }

        assertThat(sawAvailable).isTrue();
        assertThat(sawBlocked).isTrue();
    }

    @Test
    void generate_always_deadlineSubFlagsOnlyMeaningfulForTheirOwnBucket() {
        List<TaskSeed> tasks = TaskGenerator.generate(SAMPLE_SIZE, new Random(DatasetConfig.SEED));

        for (TaskSeed task : tasks) {
            DeadlineSeed deadline = task.deadline();
            if (deadline.withinHours()) {
                assertThat(deadline.bucket()).isEqualTo(DatasetConfig.DEADLINE_NEXT_7_DAYS);
            }
            if (deadline.extremeOverdue()) {
                assertThat(deadline.bucket()).isEqualTo(DatasetConfig.DEADLINE_OVERDUE);
            }
            if (deadline.bucket() == null) {
                assertThat(deadline.hasDeadline()).isFalse();
            } else {
                assertThat(deadline.hasDeadline()).isTrue();
                assertThat(DatasetConfig.DEADLINE).containsKey(deadline.bucket());
            }
        }
    }

    @Test
    void generate_always_priorityScoreAndUrgencyStayInRangeWhenPresent() {
        List<TaskSeed> tasks = TaskGenerator.generate(SAMPLE_SIZE, new Random(DatasetConfig.SEED));

        for (TaskSeed task : tasks) {
            if (task.priorityScore() != null) {
                assertThat(task.priorityScore()).isBetween(
                        DatasetConfig.PRIORITY_SCORE_RANGE.min(), DatasetConfig.PRIORITY_SCORE_RANGE.max());
            }
            if (task.urgency() != null) {
                assertThat(task.urgency()).isBetween(
                        DatasetConfig.URGENCY_RANGE.min(), DatasetConfig.URGENCY_RANGE.max());
            }
        }
    }

    @Test
    void generate_always_priorityScorePresenceShareIsCloseToTarget() {
        List<TaskSeed> tasks = TaskGenerator.generate(SAMPLE_SIZE, new Random(DatasetConfig.SEED));

        long present = tasks.stream().filter(t -> t.priorityScore() != null).count();
        double actualShare = (double) present / SAMPLE_SIZE;

        // Loose tolerance — this approach (independent per-task draws, design doc §6) targets
        // "close to", not exact. A deviation this large would itself signal a bug.
        assertThat(actualShare).isCloseTo(DatasetConfig.PRIORITY_SCORE_PRESENT_SHARE, org.assertj.core.data.Offset.offset(0.02));
    }

    @Test
    void generate_always_effortEstimateMinutesInRangeWhenPresentElseConfidenceIsLow() {
        List<TaskSeed> tasks = TaskGenerator.generate(SAMPLE_SIZE, new Random(DatasetConfig.SEED));

        for (TaskSeed task : tasks) {
            if (task.effortEstimateMinutes() != null) {
                assertThat(task.effortEstimateMinutes()).isBetween(
                        DatasetConfig.EFFORT_ESTIMATE_MINUTES.min(), DatasetConfig.EFFORT_ESTIMATE_MINUTES.max());
                assertThat(task.effortEstimateConfidence())
                        .isBetween(DatasetConfig.EFFORT_ESTIMATE_CONFIDENCE_WHEN_PRESENT.min(),
                                DatasetConfig.EFFORT_ESTIMATE_CONFIDENCE_WHEN_PRESENT.max());
            } else {
                assertThat(task.effortEstimateConfidence())
                        .isBetween(DatasetConfig.EFFORT_ESTIMATE_CONFIDENCE_WHEN_ABSENT.min(),
                                DatasetConfig.EFFORT_ESTIMATE_CONFIDENCE_WHEN_ABSENT.max());
            }
        }
    }

    @Test
    void generate_always_someMinimalRowsExist() {
        List<TaskSeed> tasks = TaskGenerator.generate(SAMPLE_SIZE, new Random(DatasetConfig.SEED));

        long minimalRows = tasks.stream()
                .filter(t -> t.tier() == null && t.roleContext() == null && !t.deadline().hasDeadline()
                        && t.priorityScore() == null && t.urgency() == null)
                .count();

        // ~2% of 50,000 is ~1,000 — assert only that the edge case actually shows up, not an
        // exact count (some non-minimal tasks legitimately land on all-null by coincidence too).
        assertThat(minimalRows).isGreaterThan(0);
    }

    @Test
    void generate_calledTwiceWithFreshSameSeed_producesIdenticalOutput() {
        List<TaskSeed> first = TaskGenerator.generate(2000, new Random(DatasetConfig.SEED));
        List<TaskSeed> second = TaskGenerator.generate(2000, new Random(DatasetConfig.SEED));

        assertThat(first).isEqualTo(second);
    }
}
