package com.rnd.chain.testsupport;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Test clock that advances by 1ms per call, so ordering assertions don't depend on real clock resolution. */
public final class IncrementingClock extends Clock {

    private final ZoneId zone;
    private Instant next;

    public IncrementingClock() {
        this(ZoneOffset.UTC, Instant.EPOCH);
    }

    private IncrementingClock(ZoneId zone, Instant next) {
        this.zone = zone;
        this.next = next;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new IncrementingClock(zone, next);
    }

    @Override
    public Instant instant() {
        Instant current = next;
        next = next.plusMillis(1);
        return current;
    }
}
