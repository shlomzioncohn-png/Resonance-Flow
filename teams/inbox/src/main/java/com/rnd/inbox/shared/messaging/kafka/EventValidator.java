package com.rnd.inbox.shared.messaging.kafka;

public interface EventValidator<T> {

    void validate(T event);
}
