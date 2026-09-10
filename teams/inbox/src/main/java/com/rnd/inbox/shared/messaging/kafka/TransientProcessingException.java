package com.rnd.inbox.shared.messaging.kafka;

public class TransientProcessingException extends RuntimeException {

    public TransientProcessingException(String message, Throwable cause) {
        super(message, cause);
    }
}
