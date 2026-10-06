package com.rnd.inbox.task.event;
import com.rnd.inbox.event.TaskCompletedEvent;
import com.rnd.inbox.shared.event.EventType;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaskCompletedProtoMapperTest {

    private final TaskCompletedProtoMapper mapper = new TaskCompletedProtoMapper();

    @Test
    void supportedType_returnsTaskCompleted() {
        assertThat(mapper.supportedType()).isEqualTo(EventType.TASK_COMPLETED);
    }

    @Test
    void toProto_mapsTaskIdAndIsCompletedTrue() {
        UUID taskId = UUID.randomUUID();
        Map<String, Object> payload = payload(taskId, true);

        TaskCompletedEvent event = mapper.toProto(payload);

        assertThat(event.getTaskId()).isEqualTo(taskId.toString());
        assertThat(event.getIsCompleted()).isTrue();
    }

    @Test
    void toProto_mapsIsCompletedFalse_doesNotHardcodeTrue() {
        Map<String, Object> payload = payload(UUID.randomUUID(), false);

        TaskCompletedEvent event = mapper.toProto(payload);

        assertThat(event.getIsCompleted()).isFalse();
    }

    @Test
    void toProto_throwsWhenTaskIdMissing() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("completed", true);

        assertThatThrownBy(() -> mapper.toProto(payload))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void toProto_throwsWhenCompletedFieldMissing() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", UUID.randomUUID().toString());

        assertThatThrownBy(() -> mapper.toProto(payload))
                .isInstanceOf(NullPointerException.class);
    }

    private Map<String, Object> payload(UUID taskId, boolean completed) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", taskId.toString());
        payload.put("completed", completed);
        return payload;
    }
}
