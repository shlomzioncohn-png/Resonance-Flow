package com.rnd.chain.events;

import com.rnd.chain.dto.TaskStatus;

/**
 * Maps Inbox's 8-value canonical task status onto Chain's own coarse 3-value lifecycle
 * (PENDING/ACTIVE/DONE). Inbox does not perform this mapping for us - task.created and
 * task.updated both carry Inbox's own {@code com.rnd.inbox.event.TaskStatus} verbatim (confirmed:
 * both .proto files declare the identical 8-value enum).
 *
 * Confirmed rule, final (team lead, overriding an earlier answer during initial planning that
 * scoped this to DONE only): DONE, CANCELLED, and REJECTED all release (unblock) a task's
 * dependents - a blocking task that's been abandoned should stop blocking its dependents, same as
 * one that's been completed. Chain's 3-value model has no separate "abandoned" state, so all three
 * map to Chain's DONE - {@code TaskEligibilityService.onTaskStatusChanged} already implements the
 * release/re-block logic structurally (it only reacts to entering/leaving DONE), so folding these
 * three into DONE gets the right behavior without adding a 4th status to Task/TaskStatus, which
 * would be a far larger, out-of-scope change. Everything else maps to PENDING/ACTIVE, neither of
 * which is individually confirmed value-by-value - not expected to matter, since
 * TaskEligibilityService only distinguishes "is DONE" from "is not DONE" for blocking purposes.
 */
final class InboxTaskStatusMapper {

    private InboxTaskStatusMapper() {
    }

    static TaskStatus map(com.rnd.inbox.event.TaskStatus inboxStatus) {
        return switch (inboxStatus) {
            case TASK_STATUS_DONE, TASK_STATUS_CANCELLED, TASK_STATUS_REJECTED -> TaskStatus.DONE;
            case TASK_STATUS_IN_PROGRESS -> TaskStatus.ACTIVE;
            case TASK_STATUS_NEW, TASK_STATUS_IN_REVIEW, TASK_STATUS_APPROVED, TASK_STATUS_SNOOZED,
                 TASK_STATUS_UNSPECIFIED, UNRECOGNIZED -> TaskStatus.PENDING;
        };
    }
}
