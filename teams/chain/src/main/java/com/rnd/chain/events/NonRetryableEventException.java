package com.rnd.chain.events;

/**
 * Thrown when an incoming Kafka message cannot be processed no matter how
 * many times it is retried - malformed JSON, a missing or invalid eventId /
 * eventType / occurredAt, or a payload that doesn't match the target
 * handler's expected type.
 *
 * Registered as a not-retryable exception on
 * ChainKafkaErrorHandlingConfig's DefaultErrorHandler, so messages that fail
 * this way go straight to the DLT instead of wasting the configured retry
 * attempts (3 attempts, 2 seconds apart) on something that will never
 * succeed. Deliberately thrown only for parsing/routing failures in
 * ChainEventConsumer - a failure inside handler.handle() itself (e.g. a
 * future handler hitting a database timeout) is exactly the transient case
 * that should keep retrying, so it is left to propagate as whatever
 * exception the handler throws.
 */
public class NonRetryableEventException extends RuntimeException {

    public NonRetryableEventException(String message) {
        super(message);
    }

    public NonRetryableEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
