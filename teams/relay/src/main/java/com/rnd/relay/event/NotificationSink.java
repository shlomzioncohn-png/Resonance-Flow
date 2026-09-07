package com.rnd.relay.event;

// Real implementation is the WebSocket registry; kept as an interface so the routing policy below is testable without it.
public interface NotificationSink {

    // offset is the Kafka partition offset the event was read at — the wire envelope's "id", and
    // the resume token a reconnecting client will eventually send back as Last-Event-ID.
    void activity(TaskNotification event, long offset);

    void refresh(String orgId, String userId);
}
