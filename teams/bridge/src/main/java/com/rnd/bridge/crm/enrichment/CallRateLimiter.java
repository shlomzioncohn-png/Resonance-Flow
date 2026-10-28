package com.rnd.bridge.crm.enrichment;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

/**
 * Minimal spacing between real {@code crm-deal-enrichment} model calls, so a CRM sync
 * never exceeds the model's own requests-per-minute limit - the live check against
 * gemini-3.5-flash-lite (RPM 15) hit exactly this: a sync with ~16 calls in under 15
 * seconds got a real 429, then Bridge's own spent-quota fast-fail skipped the rest of
 * that sync's calls. Only real calls wait - a cache hit or a skipped (terminal/blank)
 * deal never reaches {@link #awaitTurn()} (see {@code CrmDealLlmEnricher}).
 * <p>
 * {@code minInterval} zero or negative disables this entirely (no timestamp tracking,
 * no sleep) - the convenient default for every test that isn't specifically about
 * throttling.
 * <p>
 * The sleep function is a plain {@code Consumer<Duration>}, the same shape
 * {@code com.rnd.bridge.ai.provider.OpenAiCompatibleProvider} already uses for its own
 * retry backoff - mirrored here rather than inventing a new abstraction.
 */
final class CallRateLimiter {

    private final Duration minInterval;
    private final Clock clock;
    private final Consumer<Duration> sleeper;
    private Instant lastCallStartedAt;

    CallRateLimiter(Duration minInterval, Clock clock, Consumer<Duration> sleeper) {
        this.minInterval = minInterval;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /** @throws CallRateLimiterInterruptedException if the wait is interrupted - the caller's own
     *          fallback-to-ETL path (see {@code CrmDealLlmEnricher}) is expected to catch this. */
    synchronized void awaitTurn() {
        if (minInterval == null || !minInterval.isPositive()) {
            return;
        }
        if (lastCallStartedAt != null) {
            Duration elapsed = Duration.between(lastCallStartedAt, clock.instant());
            Duration remaining = minInterval.minus(elapsed);
            if (remaining.isPositive()) {
                // If this throws, lastCallStartedAt is deliberately left unset below - an
                // interrupted wait didn't actually happen, so it must not look like it did.
                sleeper.accept(remaining);
            }
        }
        lastCallStartedAt = clock.instant();
    }

    static Consumer<Duration> realSleeper() {
        return duration -> {
            try {
                Thread.sleep(duration.toMillis());
            } catch (InterruptedException e) {
                // Restore the flag, then abort instead of returning normally - letting the
                // caller proceed here would mean it never actually waited out the spacing.
                Thread.currentThread().interrupt();
                throw new CallRateLimiterInterruptedException(e);
            }
        };
    }
}
