package com.rnd.inbox.auth.service;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LoginAttemptLimiter {

    static final int MAX_FAILURES = 5;
    static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();
    private final Clock clock;

    public LoginAttemptLimiter(Clock clock) {
        this.clock = clock;
    }

    public boolean isLocked(String login) {
        Attempts current = attempts.get(login);
        if (current == null || current.lockedUntil() == null) {
            return false;
        }
        if (Instant.now(clock).isBefore(current.lockedUntil())) {
            return true;
        }
        attempts.remove(login);
        return false;
    }

    public void recordFailure(String login) {
        attempts.compute(login, (key, current) -> {
            int failures = current == null ? 1 : current.failures() + 1;
            Instant lockedUntil = failures >= MAX_FAILURES ? Instant.now(clock).plus(LOCK_DURATION) : null;
            return new Attempts(failures, lockedUntil);
        });
    }

    public void recordSuccess(String login) {
        attempts.remove(login);
    }

    private record Attempts(int failures, Instant lockedUntil) {
    }
}
