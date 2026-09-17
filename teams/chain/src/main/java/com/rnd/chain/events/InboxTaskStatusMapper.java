package com.rnd.chain.events;

import com.rnd.chain.dto.TaskStatus;

/**
 * Maps Inbox's 8-value canonical task status onto Chain's own coarse 3-value lifecycle
 * (PENDING/ACTIVE/DONE). Inbox does not perform this mapping for us - task.created and
 * task.updated both carry Inbox's own {@code com.rnd.inbox.event.TaskStatus} verbatim (confirmed:
 * both .proto files declare the identical 8-value enum).
 *
 * Confirmed rule (the only value-by-value fact actually confirmed, not the mapping table as a
 * whole): only a transition <em>to</em> DONE releases (unblocks) a task's dependents -
 * {@code TaskEligibilityService.onTaskStatusChanged} already implements this structurally, since
 * it only reacts to entering/leaving DONE, regardless of what the other bucket is called. The
 * mapping below for every status other than DONE is this plan's own reasonable default, not
 * individually confirmed - flagged in the implementation plan for a sanity check with Inbox
 * before being treated as final.
 */
final class InboxTaskStatusMapper {

    private InboxTaskStatusMapper() {
    }

    static TaskStatus map(com.rnd.inbox.event.TaskStatus inboxStatus) {
        return switch (inboxStatus) {
            case TASK_STATUS_DONE -> TaskStatus.DONE;
            case TASK_STATUS_IN_PROGRESS -> TaskStatus.ACTIVE;
            case TASK_STATUS_NEW, TASK_STATUS_IN_REVIEW, TASK_STATUS_APPROVED, TASK_STATUS_SNOOZED,
                 TASK_STATUS_REJECTED, TASK_STATUS_CANCELLED, TASK_STATUS_UNSPECIFIED, UNRECOGNIZED ->
                    TaskStatus.PENDING;
        };
    }
}
