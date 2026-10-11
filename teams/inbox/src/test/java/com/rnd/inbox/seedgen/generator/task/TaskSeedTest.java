package com.rnd.inbox.seedgen.generator.task;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class TaskSeedTest {

    @Test
    void constructor_validValues_doesNotThrow() {
        assertThatCode(() -> new TaskSeed(
                "new", "email", "manual", null, null, null, DeadlineSeed.NONE, true, null, null, null, 0.05))
                .doesNotThrowAnyException();
    }

    @Test
    void constructor_nullDeadline_throws() {
        assertThatIllegalArgumentException().isThrownBy(() -> new TaskSeed(
                "new", "email", "manual", null, null, null, null, true, null, null, null, 0.05));
    }

    @Test
    void constructor_confidenceOutOfRange_throws() {
        assertThatIllegalArgumentException().isThrownBy(() -> new TaskSeed(
                "new", "email", "ml_inferred", 1.5, null, null, DeadlineSeed.NONE, true, null, null, null, 0.05));
    }

    @Test
    void constructor_priorityScoreOutOfRange_throws() {
        assertThatIllegalArgumentException().isThrownBy(() -> new TaskSeed(
                "new", "email", "manual", null, null, null, DeadlineSeed.NONE, true, 11, null, null, 0.05));
    }

    @Test
    void constructor_urgencyOutOfRange_throws() {
        assertThatIllegalArgumentException().isThrownBy(() -> new TaskSeed(
                "new", "email", "manual", null, null, null, DeadlineSeed.NONE, true, null, 6, null, 0.05));
    }

    @Test
    void constructor_nonPositiveEffortEstimateMinutes_throws() {
        assertThatIllegalArgumentException().isThrownBy(() -> new TaskSeed(
                "new", "email", "manual", null, null, null, DeadlineSeed.NONE, true, null, null, 0, 0.05));
    }

    @Test
    void constructor_effortEstimateConfidenceOutOfRange_throws() {
        assertThatIllegalArgumentException().isThrownBy(() -> new TaskSeed(
                "new", "email", "manual", null, null, null, DeadlineSeed.NONE, true, null, null, null, 1.5));
    }

    @Test
    void constructor_blankStatus_throws() {
        assertThatIllegalArgumentException().isThrownBy(() -> new TaskSeed(
                " ", "email", "manual", null, null, null, DeadlineSeed.NONE, true, null, null, null, 0.05));
    }
}
