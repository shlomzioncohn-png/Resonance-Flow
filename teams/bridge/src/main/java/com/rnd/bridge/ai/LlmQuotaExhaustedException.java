package com.rnd.bridge.ai;

import java.time.Instant;

/**
 * No quota left to call this model until {@link #retryAt()}: the provider said its (free-tier)
 * quota is spent, or the usage reached its own bridge.ai daily-call-limit. Nothing was answered,
 * and calling again before retryAt only fails the same way.
 * <p>
 * What to do with the item is the caller's decision, not the AI layer's: media classification
 * publishes it at once, INCLUDED and unclassified, and logs CLASSIFICATION_SKIPPED_QUOTA.
 * <p>
 * {@code retryable} still follows {@link LlmException}: true for a provider's spent quota (a
 * fallback model has a quota of its own), false for Bridge's own daily limit (it covers the whole
 * usage, every fallback included).
 */
public class LlmQuotaExhaustedException extends LlmException {

    private final Instant retryAt;

    public LlmQuotaExhaustedException(String message, Instant retryAt, boolean retryable, Throwable cause) {
        super(message, retryable, cause);
        this.retryAt = retryAt;
    }

    /** When the quota renews - the provider's own estimate, or the next UTC midnight. */
    public Instant retryAt() {
        return retryAt;
    }
}
