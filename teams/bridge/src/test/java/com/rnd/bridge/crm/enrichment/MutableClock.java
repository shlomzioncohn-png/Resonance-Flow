package com.rnd.bridge.crm.enrichment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Test-only: a {@link Clock} whose {@link #instant()} only moves when {@link #advance} is called. */
final class MutableClock extends Clock {

    private Instant now;

    MutableClock(Instant now) {
        this.now = now;
    }

    void advance(Duration by) {
        now = now.plus(by);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
