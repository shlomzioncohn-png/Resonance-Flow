package com.rnd.chain.events;

import com.rnd.chain.dto.TaskTier;
import com.rnd.inbox.event.TaskUpdatedEvent;

/**
 * Maps Inbox's optional tier onto {@link TaskTier}. A missing, UNSPECIFIED or UNRECOGNIZED tier
 * maps to null, which never overwrites a stored tier - a stored tier cannot be cleared.
 */
final class InboxTaskTierMapper {

    private InboxTaskTierMapper() {
    }

    static TaskTier map(TaskUpdatedEvent event) {
        if (!event.hasTier()) {
            return null;
        }
        return switch (event.getTier()) {
            case TASK_TIER_OPERATIONAL -> TaskTier.OPERATIONAL;
            case TASK_TIER_TACTICAL -> TaskTier.TACTICAL;
            case TASK_TIER_STRATEGIC -> TaskTier.STRATEGIC;
            case TASK_TIER_UNSPECIFIED, UNRECOGNIZED -> null;
        };
    }
}
