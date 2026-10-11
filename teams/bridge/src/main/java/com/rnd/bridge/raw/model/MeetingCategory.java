package com.rnd.bridge.raw.model;

/** {@link ItemCategory} values for {@link RawSourceType#MEETING}. */
public enum MeetingCategory implements ItemCategory {
    PLANNING,
    STANDUP,
    CUSTOMER_CALL,
    INTERVIEW,
    ONE_ON_ONE,
    SOCIAL,
    /** A mic/connection check or similar with no real content - typically decided by RULE. */
    TEST,
    OTHER;

    @Override
    public RawSourceType sourceType() {
        return RawSourceType.MEETING;
    }
}
