package com.rnd.inbox.task.event;
import com.google.protobuf.util.Timestamps;
import com.rnd.inbox.event.TaskCompletedEvent;
import com.rnd.inbox.shared.event.EventType;
import com.rnd.inbox.shared.event.ProtoEventMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

@Component
public class TaskCompletedProtoMapper implements ProtoEventMapper<TaskCompletedEvent> {

    @Override
    public EventType supportedType() {
        return EventType.TASK_COMPLETED;
    }

    @Override
    public TaskCompletedEvent toProto(Map<String, Object> payload) {
        return TaskCompletedEvent.newBuilder()
                .setTaskId((String) payload.get("taskId"))
                .setIsCompleted((Boolean) payload.get("completed"))
                .setUpdatedAt(toTimestamp((String) payload.get("updatedAt")))
                .build();
    }

    private com.google.protobuf.Timestamp toTimestamp(String isoInstant) {
        return Timestamps.fromMillis(Instant.parse(isoInstant).toEpochMilli());
    }
}
