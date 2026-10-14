package com.rnd.chain.events;

public interface SchedulingUpdatePublisher {

    void publish(TaskSchedulingUpdatedEvent event);
}