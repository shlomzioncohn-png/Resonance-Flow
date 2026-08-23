package com.rnd.bridge.crm.mapper;

import com.rnd.bridge.crm.model.Deal;
import com.rnd.bridge.crm.model.DealStage;
import com.rnd.bridge.dto.TaskStatus;

public final class DealToTaskMapper {

    private static final String SOURCE_REF_PREFIX = "crm:";

    private DealToTaskMapper() {
    }

    public static TaskStatus toTaskStatus(DealStage stage) {
        return switch (stage) {
            case PROSPECT, QUALIFIED, PROPOSAL, NEGOTIATION -> TaskStatus.NEW;
            // Inbox requires snoozed_until whenever status = SNOOZED, but a
            // Deal carries no resume date (closeDate is a target close date,
            // not a snooze-until date). Open question for Inbox: what
            // populates snoozed_until for an ON_HOLD deal? Not inventing one.
            case ON_HOLD -> TaskStatus.SNOOZED;
            case CLOSED_WON -> TaskStatus.DONE;
            case CLOSED_LOST -> TaskStatus.CANCELLED;
        };
    }

    public static String toSourceRef(Deal deal) {
        return SOURCE_REF_PREFIX + deal.dealId();
    }
}
