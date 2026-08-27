package com.rnd.inbox.event;

import java.util.concurrent.CompletableFuture;

public interface EventPublisher {

    <T> CompletableFuture<Void> publish(String topic, String key, Event<T> event);
}
