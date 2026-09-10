package com.rnd.inbox.shared.event;

public enum EventType {

    TASK_CREATED("task.created"),
    TASK_UPDATED("task.updated"),
    TASK_COMPLETED("task.completed");

    private final String topic;

    EventType(String topic) {
        this.topic = topic;
    }

    public String getTopic() {
        return topic;
    }
}
