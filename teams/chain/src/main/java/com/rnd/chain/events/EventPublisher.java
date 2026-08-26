package com.rnd.chain.events;

public interface EventPublisher {

    void publish(EventEnvelope<?> event);
}

