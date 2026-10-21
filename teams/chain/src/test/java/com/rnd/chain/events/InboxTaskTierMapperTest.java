package com.rnd.chain.events;

import com.rnd.chain.dto.TaskTier;
import com.rnd.inbox.event.TaskUpdatedEvent;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InboxTaskTierMapperTest {

    private static TaskUpdatedEvent.Builder base() {
        return TaskUpdatedEvent.newBuilder();
    }

    @Test
    void map_returnsNull_whenTierIsNotSet() {
        assertThat(InboxTaskTierMapper.map(base().build())).isNull();
    }

    @Test
    void map_mapsEachKnownTier() {
        assertThat(InboxTaskTierMapper.map(base().setTier(com.rnd.inbox.event.TaskTier.TASK_TIER_OPERATIONAL).build()))
                .isEqualTo(TaskTier.OPERATIONAL);
        assertThat(InboxTaskTierMapper.map(base().setTier(com.rnd.inbox.event.TaskTier.TASK_TIER_TACTICAL).build()))
                .isEqualTo(TaskTier.TACTICAL);
        assertThat(InboxTaskTierMapper.map(base().setTier(com.rnd.inbox.event.TaskTier.TASK_TIER_STRATEGIC).build()))
                .isEqualTo(TaskTier.STRATEGIC);
    }

    @Test
    void map_returnsNull_whenTierIsExplicitlyUnspecified() {
        assertThat(InboxTaskTierMapper.map(
                base().setTier(com.rnd.inbox.event.TaskTier.TASK_TIER_UNSPECIFIED).build())).isNull();
    }

    @Test
    void map_returnsNull_whenTierValueIsUnrecognized() {
        assertThat(InboxTaskTierMapper.map(base().setTierValue(99).build())).isNull();
    }
}
