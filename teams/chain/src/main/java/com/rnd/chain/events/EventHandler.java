package com.rnd.chain.events;

/**
 * Interface implemented by every handler for a specific event type.
 * For example: TaskCreatedEventHandler, DependencyProposedEventHandler.
 *
 * @param <T> the payload type this handler knows how to process
 */
public interface EventHandler<T> {

    /**
     * The event type this handler processes (e.g. "task.created").
     * Used by the Consumer to route each incoming event to the right handler.
     */
    String getSupportedEventType();

    /**
     * The concrete payload class, needed for correct deserialization
     * before handle is called.
     */
    Class<T> getPayloadType();

    /**
     * The actual event handling logic - the specific business logic.
     */
    void handle(EventEnvelope<T> event);
}