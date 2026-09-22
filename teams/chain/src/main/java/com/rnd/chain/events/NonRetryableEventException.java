package com.rnd.chain.events;

/**
 * Thrown when an incoming Kafka message cannot be processed no matter how many times it is
 * retried - an invalid UUID field, an unrecognized enum value, a proposed dependency that would
 * create a cycle.
 *
 * Each handler's {@code @RetryableTopic} only lists {@link ChainTransientProcessingException} (and
 * its subtypes) in its {@code include}; this type deliberately isn't one of them, so it skips
 * straight to the {@code .errors} topic instead of wasting retry attempts on something that will
 * never succeed. Contrast with {@link ChainTransientProcessingException} for the retryable case
 * (a referenced task hasn't landed yet, a transient DB failure).
 */
public class NonRetryableEventException extends RuntimeException {

    public NonRetryableEventException(String message) {
        super(message);
    }

    public NonRetryableEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
