package com.rnd.bridge.crm.enrichment;

/**
 * Thrown by {@link CallRateLimiter#awaitTurn()} when its wait is interrupted before the
 * required spacing has elapsed. Unchecked on purpose: it propagates through
 * {@code CrmDealLlmEnricher}'s normal call path and is caught by that class's existing
 * catch-all fallback to the ETL result, the same as any other enrichment failure - there
 * is nothing a caller could usefully do differently for this one.
 */
final class CallRateLimiterInterruptedException extends RuntimeException {

    CallRateLimiterInterruptedException(InterruptedException cause) {
        super("Interrupted while waiting for the CRM enrichment call-rate limiter", cause);
    }
}
