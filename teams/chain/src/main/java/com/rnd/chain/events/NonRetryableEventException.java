package com.rnd.chain.events;

/**
 * Thrown when an incoming Kafka message cannot be processed no matter how many times it is
 * retried - an invalid UUID field, an unrecognized enum value, a proposed dependency that would
 * create a cycle.
 *
 * Each handler's {@code @RetryableTopic} excludes this type, so it skips straight to the
 * {@code .errors} topic instead of wasting retry attempts on something that will never succeed.
 * Every other exception is retried - see {@link ChainEventProcessingTemplate}.
 */
public class NonRetryableEventException extends RuntimeException {

    public NonRetryableEventException(String message) {
        super(message);
    }

    public NonRetryableEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
