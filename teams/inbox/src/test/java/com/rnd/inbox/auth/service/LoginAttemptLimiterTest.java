package com.rnd.inbox.auth.service;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class LoginAttemptLimiterTest {

    private static final String LOGIN = "rachel";

    private MutableClock clock;
    private LoginAttemptLimiter limiter;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-18T09:00:00Z"));
        limiter = new LoginAttemptLimiter(clock);
    }

    private void fail(int times) {
        for (int i = 0; i < times; i++) {
            limiter.recordFailure(LOGIN);
        }
    }

    @Test
    void isLocked_belowMaxFailures_returnsFalse() {
        fail(LoginAttemptLimiter.MAX_FAILURES - 1);

        assertThat(limiter.isLocked(LOGIN)).isFalse();
    }

    @Test
    void isLocked_atMaxFailures_returnsTrue() {
        fail(LoginAttemptLimiter.MAX_FAILURES);

        assertThat(limiter.isLocked(LOGIN)).isTrue();
        assertThat(limiter.isLocked("someone-else")).isFalse();
    }

    @Test
    void isLocked_afterLockDuration_returnsFalseAndResetsFailures() {
        fail(LoginAttemptLimiter.MAX_FAILURES);
        clock.advance(LoginAttemptLimiter.LOCK_DURATION);

        assertThat(limiter.isLocked(LOGIN)).isFalse();
        fail(1);
        assertThat(limiter.isLocked(LOGIN)).isFalse();
    }

    @Test
    void recordSuccess_resetsFailures() {
        fail(LoginAttemptLimiter.MAX_FAILURES - 1);
        limiter.recordSuccess(LOGIN);
        fail(1);

        assertThat(limiter.isLocked(LOGIN)).isFalse();
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
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
}
