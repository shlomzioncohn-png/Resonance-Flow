package pipe;

import com.rnd.relay.event.PushPolicy;
import com.rnd.relay.event.TaskNotification;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// design doc §08 "the payload remains deliberately thin"
class NotificationPayloadsTest {

    @Test
    void payloadCarriesOnlyTheClientFacingFields() {
        TaskNotification event = TaskNotification.newBuilder()
                .setEventId("event-1")
                .setOrgId("org-1")
                .setUserId("user-1")
                .setTaskId("task-1")
                .setPushPolicy(PushPolicy.ACTIVITY)
                .setUrgencyScore(7.5)
                .setEffortEstimateMinutes(30)
                .build();

        Map<String, Object> payload = NotificationPayloads.toMap(event, 42L);

        assertThat(payload).containsOnlyKeys("offset", "task_id", "urgency_score", "effort_estimate_minutes");
        assertThat(payload).containsEntry("offset", 42L);
        assertThat(payload).containsEntry("task_id", "task-1");
        assertThat(payload).containsEntry("urgency_score", 7.5);
        assertThat(payload).containsEntry("effort_estimate_minutes", 30);
    }

    @Test
    void sourceRefIsIncludedOnlyWhenTheProducerSetIt() {
        TaskNotification withSourceRef = TaskNotification.newBuilder()
                .setTaskId("task-1")
                .setSourceRef("chain:task_4471")
                .build();

        Map<String, Object> payload = NotificationPayloads.toMap(withSourceRef, 1L);

        assertThat(payload).containsEntry("source_ref", "chain:task_4471");
    }

    @Test
    void emptySourceRefIsOmittedRatherThanSentAsBlank() {
        TaskNotification withoutSourceRef = TaskNotification.newBuilder()
                .setTaskId("task-1")
                .build();

        Map<String, Object> payload = NotificationPayloads.toMap(withoutSourceRef, 1L);

        assertThat(payload).doesNotContainKey("source_ref");
    }
}
