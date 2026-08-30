package com.rnd.inbox.messaging.kafka;

public interface EventValidator<T> {

    void validate(T event);
}
