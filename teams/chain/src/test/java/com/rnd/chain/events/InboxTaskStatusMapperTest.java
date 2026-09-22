package com.rnd.chain.events;

import com.rnd.chain.dto.TaskStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InboxTaskStatusMapperTest {

    @Test
    void done_mapsToChainDone() {
        assertEquals(TaskStatus.DONE, InboxTaskStatusMapper.map(com.rnd.inbox.event.TaskStatus.TASK_STATUS_DONE));
    }

    @Test
    void cancelled_mapsToChainDone_soItReleasesDependentsLikeDone() {
        assertEquals(TaskStatus.DONE, InboxTaskStatusMapper.map(com.rnd.inbox.event.TaskStatus.TASK_STATUS_CANCELLED));
    }

    @Test
    void rejected_mapsToChainDone_soItReleasesDependentsLikeDone() {
        assertEquals(TaskStatus.DONE, InboxTaskStatusMapper.map(com.rnd.inbox.event.TaskStatus.TASK_STATUS_REJECTED));
    }

    @Test
    void inProgress_mapsToChainActive() {
        assertEquals(TaskStatus.ACTIVE, InboxTaskStatusMapper.map(com.rnd.inbox.event.TaskStatus.TASK_STATUS_IN_PROGRESS));
    }

    @Test
    void everyOtherStatus_mapsToChainPending() {
        assertEquals(TaskStatus.PENDING, InboxTaskStatusMapper.map(com.rnd.inbox.event.TaskStatus.TASK_STATUS_NEW));
        assertEquals(TaskStatus.PENDING, InboxTaskStatusMapper.map(com.rnd.inbox.event.TaskStatus.TASK_STATUS_IN_REVIEW));
        assertEquals(TaskStatus.PENDING, InboxTaskStatusMapper.map(com.rnd.inbox.event.TaskStatus.TASK_STATUS_APPROVED));
        assertEquals(TaskStatus.PENDING, InboxTaskStatusMapper.map(com.rnd.inbox.event.TaskStatus.TASK_STATUS_SNOOZED));
        assertEquals(TaskStatus.PENDING, InboxTaskStatusMapper.map(com.rnd.inbox.event.TaskStatus.TASK_STATUS_UNSPECIFIED));
    }
}
