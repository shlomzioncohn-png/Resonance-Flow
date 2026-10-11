package pipe;

import com.rnd.relay.event.TaskNotification;

public interface NotificationSink {

    void activity(TaskNotification event, long offset);

    void refresh(String orgId, String userId);
}
