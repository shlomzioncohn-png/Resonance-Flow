package com.rnd.chain.event;

public interface SchedulingUpdatePublisher {

    void publish(TaskSchedulingUpdatedEvent event);
}