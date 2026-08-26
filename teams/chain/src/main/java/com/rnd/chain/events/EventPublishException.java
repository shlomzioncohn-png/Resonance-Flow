package com.rnd.chain.events;

/**
 * Thrown when publishing an event fails - either due to a serialization
 * failure or because no topic is configured for the event type.
 */
public class EventPublishException extends RuntimeException {

    public EventPublishException(String message) {
        super(message);
    }

    public EventPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}