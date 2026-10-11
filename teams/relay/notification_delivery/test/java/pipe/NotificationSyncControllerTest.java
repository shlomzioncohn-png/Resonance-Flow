package pipe;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationSyncControllerTest {

    private static final String ORG_ID = "org-1";
    private static final String USER_ID = "user-1";

    @Mock
    ActivityReplayBuffer replayBuffer;

    @Mock
    KafkaNotificationReplayService kafkaReplayService;

    @Test
    void returnsEntriesRecordedSinceTheGivenInstantAsJsonPayloads() {
        NotificationSyncController controller =
                new NotificationSyncController(replayBuffer, kafkaReplayService, new TrustedHeaderAuthenticator());
        TaskNotification event = TaskNotification.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setOrgId(ORG_ID)
                .setUserId(USER_ID)
                .setTaskId("task-1")
                .setPushPolicy(PushPolicy.ACTIVITY)
                .build();
        Instant since = Instant.parse("2026-08-30T09:00:00Z");
        when(replayBuffer.since(ORG_ID, USER_ID, Instant.ofEpochMilli(since.toEpochMilli())))
                .thenReturn(Optional.of(List.of(new ActivityReplayBuffer.Entry(7L, event, since.plusSeconds(1)))));

        List<Map<String, Object>> result = controller.sync(ORG_ID, USER_ID, since.toEpochMilli());

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsEntry("task_id", "task-1").containsEntry("offset", 7L);
        verify(replayBuffer).since(ORG_ID, USER_ID, Instant.ofEpochMilli(since.toEpochMilli()));
        verify(kafkaReplayService, never()).replaySince(ORG_ID, USER_ID, since);
    }

    @Test
    void blankIdentityHeadersAreRejectedWithBadRequest() {
        NotificationSyncController controller =
                new NotificationSyncController(replayBuffer, kafkaReplayService, new TrustedHeaderAuthenticator());

        assertThatThrownBy(() -> controller.sync(" ", USER_ID, 0L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> controller.sync(ORG_ID, "", 0L))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(replayBuffer, kafkaReplayService);
    }

    @Test
    void fallsBackToKafkaWhenTheLocalBufferCannotProveItHasEverything() {
        NotificationSyncController controller =
                new NotificationSyncController(replayBuffer, kafkaReplayService, new TrustedHeaderAuthenticator());
        TaskNotification event = TaskNotification.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setOrgId(ORG_ID)
                .setUserId(USER_ID)
                .setTaskId("task-1")
                .setPushPolicy(PushPolicy.ACTIVITY)
                .build();
        Instant since = Instant.parse("2026-08-30T09:00:00Z");
        when(replayBuffer.since(ORG_ID, USER_ID, Instant.ofEpochMilli(since.toEpochMilli())))
                .thenReturn(Optional.empty());
        when(kafkaReplayService.replaySince(ORG_ID, USER_ID, since))
                .thenReturn(List.of(new ActivityReplayBuffer.Entry(7L, event, since.plusSeconds(1))));

        List<Map<String, Object>> result = controller.sync(ORG_ID, USER_ID, since.toEpochMilli());

        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsEntry("task_id", "task-1").containsEntry("offset", 7L);
    }
}
