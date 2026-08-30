package com.rnd.inbox.messaging.kafka;

public class TransientProcessingException extends RuntimeException {

    public TransientProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
