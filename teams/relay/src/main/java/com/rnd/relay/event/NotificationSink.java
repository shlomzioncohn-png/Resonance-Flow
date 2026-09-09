package com.rnd.relay.event;

public interface NotificationSink {

    void activity(TaskNotification event, long offset);

    void refresh(String orgId, String userId);
}
