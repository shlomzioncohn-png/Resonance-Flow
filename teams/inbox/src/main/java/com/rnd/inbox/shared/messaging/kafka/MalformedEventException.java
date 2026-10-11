package com.rnd.inbox.shared.messaging.kafka;

public class MalformedEventException extends RuntimeException {

    public MalformedEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
