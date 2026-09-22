package com.rnd.relay.notification_delivery;

public interface NotificationSink {

    void activity(TaskNotification event, long offset);

    void refresh(String orgId, String userId);
}
