package com.rnd.chain.events;

/**
 * נזרקת כאשר פרסום אירוע נכשל - בין אם בגלל כשל serialization
 * ובין אם בגלל שאין topic מוגדר לסוג האירוע.
 */
public class EventPublishException extends RuntimeException {

    public EventPublishException(String message) {
        super(message);
    }

    public EventPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}