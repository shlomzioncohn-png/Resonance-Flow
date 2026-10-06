package com.rnd.inbox.seedgen.generator.task;

import com.rnd.inbox.seedgen.config.DatasetConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DeadlineSeedTest {

    @Test
    void none_hasDeadlineIsFalse() {
        assertThat(DeadlineSeed.NONE.hasDeadline()).isFalse();
        assertThat(DeadlineSeed.NONE.bucket()).isNull();
    }

    @Test
    void withBucket_hasDeadlineIsTrue() {
        DeadlineSeed overdue = new DeadlineSeed(DatasetConfig.DEADLINE_OVERDUE, false, true);

        assertThat(overdue.hasDeadline()).isTrue();
        assertThat(overdue.extremeOverdue()).isTrue();
    }
}
