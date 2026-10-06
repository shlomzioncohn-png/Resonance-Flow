package com.rnd.inbox.shared.messaging.kafka;

import java.util.List;

public class CompositeEventValidator<T> implements EventValidator<T> {

    private final List<EventValidator<T>> validators;

    public CompositeEventValidator(List<EventValidator<T>> validators) {
        this.validators = List.copyOf(validators);
    }

    @Override
    public void validate(T event) {
        for (EventValidator<T> validator : validators) {
            validator.validate(event);
        }
    }
}
