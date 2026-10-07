package pipe;

public interface NotificationSink {

    void activity(TaskNotification event, long offset);

    void refresh(String orgId, String userId);
}
