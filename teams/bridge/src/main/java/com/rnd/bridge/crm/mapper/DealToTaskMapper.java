package com.rnd.bridge.crm.mapper;

import com.rnd.bridge.crm.model.Deal;
import com.rnd.bridge.crm.model.DealStage;
import com.rnd.bridge.dto.TaskStatus;

public final class DealToTaskMapper {

    private static final String ID_PREFIX = "crm:";

    private DealToTaskMapper() {
    }

    public static TaskStatus toTaskStatus(DealStage stage) {
        return switch (stage) {
            case PROSPECT, QUALIFIED, PROPOSAL, NEGOTIATION -> TaskStatus.NEW;
            case ON_HOLD -> TaskStatus.BLOCKED;
            case CLOSED_WON, CLOSED_LOST -> TaskStatus.DONE;
        };
    }

    public static String toTaskId(Deal deal) {
        return ID_PREFIX + deal.dealId();
    }
}
