package com.rnd.chain.events;

/**
 * Marks a failure as retryable: a transient condition (a referenced task hasn't landed yet, a
 * database timeout, a broker hiccup) that may well succeed if the message is retried or
 * redelivered. Never registered in DefaultErrorHandler#addNotRetryableExceptions - see
 * NonRetryableEventException for the opposite case (permanently invalid, never retried).
 */
public class ChainTransientProcessingException extends RuntimeException {

    public ChainTransientProcessingException(String message) {
        super(message);
    }

    public ChainTransientProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
