package com.rnd.bridge.raw.model;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** {@link ItemCategory} values shared by every meeting platform ({@link RawSourceType#isMeeting()}). */
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

    private static final Set<RawSourceType> SOURCE_TYPES = Arrays.stream(RawSourceType.values())
            .filter(RawSourceType::isMeeting)
            .collect(Collectors.toUnmodifiableSet());

    @Override
    public Set<RawSourceType> sourceTypes() {
        return SOURCE_TYPES;
    }
}
