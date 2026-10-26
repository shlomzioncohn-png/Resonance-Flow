package com.rnd.bridge.ai.guard;

import com.rnd.bridge.ai.LlmClient;
import com.rnd.bridge.ai.LlmQuotaExhaustedException;
import com.rnd.bridge.ai.LlmRequest;
import com.rnd.bridge.ai.LlmResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Decorator that caps one usage at {@code limit} calls per UTC day, so a flood of items can't burn
 * the free tier's quota for everyone else. Over the cap the call is refused before anything is
 * sent ({@link LlmQuotaExhaustedException}, not retryable, retryAt the next UTC midnight); the
 * count resets then. Every attempt counts, failed ones included - a failed call still spends
 * quota. In-memory, per process: a restart resets it, which errs on the side of the provider's
 * own limit still applying.
 */
public class DailyCallLimitLlmClient implements LlmClient {

    private final LlmClient delegate;
    private final String usage;
    private final int limit;
    private final Clock clock;
    private LocalDate day;
    private int calls;

    public DailyCallLimitLlmClient(LlmClient delegate, String usage, int limit, Clock clock) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive, was " + limit);
        }
        this.delegate = delegate;
        this.usage = usage;
        this.limit = limit;
        this.clock = clock;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        claimOne();
        return delegate.complete(request);
    }

    private synchronized void claimOne() {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        if (!today.equals(day)) {
            day = today;
            calls = 0;
        }
        if (calls >= limit) {
            Instant nextReset = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            throw new LlmQuotaExhaustedException("Usage '" + usage + "' reached its daily limit of " + limit
                    + " calls (bridge.ai daily-call-limit)", nextReset, false, null);
        }
        calls++;
    }
}
