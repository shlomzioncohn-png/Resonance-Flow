package com.rnd.inbox.messaging.kafka;

import java.util.List;

public class EventValidationException extends RuntimeException {

    private final List<String> fieldErrors;

    public EventValidationException(String message, List<String> fieldErrors) {
        super(message);
        this.fieldErrors = fieldErrors == null ? List.of() : List.copyOf(fieldErrors);
    }

    public List<String> getFieldErrors() {
        return fieldErrors;
    }
}
