package com.rnd.relay.event;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// design doc §04 delivery, §06 pitfalls 1 & 5, §09 "connection registration/removal" and
// "backpressure limits" unit tests.
@ExtendWith(MockitoExtension.class)
class WebSocketNotificationRegistryTest {

    private static final String ORG_ID = "org-1";
    private static final String USER_ID = "user-1";
    private static final int DEFAULT_OUTBOX_CAPACITY = 32;

    @Mock
    ActivityReplayBuffer replayBuffer;

    @Mock
    KafkaNotificationReplayService kafkaReplayService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private WebSocketNotificationRegistry newRegistry(int outboxCapacity) {
        return new WebSocketNotificationRegistry(
                replayBuffer, kafkaReplayService, objectMapper, meterRegistry, outboxCapacity);
    }

    @Test
    void registerAddsAConnectionAndUnregisterRemovesIt() throws IOException {
        WebSocketNotificationRegistry registry = newRegistry(DEFAULT_OUTBOX_CAPACITY);
        WebSocketSession session = recordingSession("s1", new CopyOnWriteArrayList<>());

        registry.register(session, ORG_ID, USER_ID, null);
        assertThat(registry.connectionCount(ORG_ID, USER_ID)).isEqualTo(1);

        registry.unregister(session);
        assertThat(registry.connectionCount(ORG_ID, USER_ID)).isEqualTo(0);
        verify(session).close();
    }

    @Test
    void unregisteringAnUnknownSessionIsANoOp() {
        WebSocketNotificationRegistry registry = newRegistry(DEFAULT_OUTBOX_CAPACITY);
        WebSocketSession unknownSession = mock(WebSocketSession.class);
        when(unknownSession.getId()).thenReturn("never-registered");

        registry.unregister(unknownSession);
    }

    @Test
    void activityIsDeliveredToEveryConnectionForThatUserAndRecordedForReplay() throws IOException {
        WebSocketNotificationRegistry registry = newRegistry(DEFAULT_OUTBOX_CAPACITY);
        List<WebSocketMessage<?>> tab1Messages = new CopyOnWriteArrayList<>();
        List<WebSocketMessage<?>> tab2Messages = new CopyOnWriteArrayList<>();
        registry.register(recordingSession("tab1", tab1Messages), ORG_ID, USER_ID, null);
        registry.register(recordingSession("tab2", tab2Messages), ORG_ID, USER_ID, null);

        TaskNotification event = notification("task-1");
        registry.activity(event, 42L);

        await().atMost(2, SECONDS).untilAsserted(() -> {
            assertThat(tab1Messages).hasSize(1);
            assertThat(tab2Messages).hasSize(1);
        });
        Map<String, Object> envelope = decode(tab1Messages.get(0));
        assertThat(envelope.get("id")).isEqualTo(42);
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) envelope.get("data");
        assertThat(data.get("task_id")).isEqualTo("task-1");
        verify(replayBuffer).record(eq(ORG_ID), eq(USER_ID), eq(event), eq(42L), any(Instant.class));
    }

    @Test
    void activityForAUserWithNoConnectionIsDroppedButStillRecordedForReplay() {
        WebSocketNotificationRegistry registry = newRegistry(DEFAULT_OUTBOX_CAPACITY);
        TaskNotification event = notification("task-1");

        registry.activity(event, 1L);

        verify(replayBuffer).record(eq(ORG_ID), eq(USER_ID), eq(event), eq(1L), any(Instant.class));
    }

    @Test
    void refreshSendsACoalescedSignalWithNoTaskPayload() throws IOException {
        WebSocketNotificationRegistry registry = newRegistry(DEFAULT_OUTBOX_CAPACITY);
        List<WebSocketMessage<?>> messages = new CopyOnWriteArrayList<>();
        registry.register(recordingSession("s1", messages), ORG_ID, USER_ID, null);

        registry.refresh(ORG_ID, USER_ID);

        await().atMost(2, SECONDS).untilAsserted(() -> assertThat(messages).hasSize(1));
        Map<String, Object> envelope = decode(messages.get(0));
        assertThat(envelope.get("event")).isEqualTo("refresh");
        assertThat((Map<?, ?>) envelope.get("data")).isEmpty();
    }

    @Test
    void refreshForADisconnectedUserIsANoOp() {
        WebSocketNotificationRegistry registry = newRegistry(DEFAULT_OUTBOX_CAPACITY);

        registry.refresh(ORG_ID, USER_ID);
    }

    @Test
    void reconnectWithALocalReplayHitFlushesMissedEntriesThenSendsARefresh() throws IOException {
        TaskNotification t6 = notification("t6");
        TaskNotification t7 = notification("t7");
        when(replayBuffer.replaySince(ORG_ID, USER_ID, 5L)).thenReturn(Optional.of(List.of(
                new ActivityReplayBuffer.Entry(6L, t6, Instant.now()),
                new ActivityReplayBuffer.Entry(7L, t7, Instant.now()))));

        WebSocketNotificationRegistry registry = newRegistry(DEFAULT_OUTBOX_CAPACITY);
        List<WebSocketMessage<?>> messages = new CopyOnWriteArrayList<>();

        registry.register(recordingSession("s1", messages), ORG_ID, USER_ID, 5L);

        await().atMost(2, SECONDS).untilAsserted(() -> assertThat(messages).hasSize(3));
        assertThat(decode(messages.get(0)).get("id")).isEqualTo(6);
        assertThat(decode(messages.get(1)).get("id")).isEqualTo(7);
        assertThat(decode(messages.get(2)).get("event")).isEqualTo("refresh");
        verify(kafkaReplayService, never()).replaySince(anyString(), anyString(), anyLong());
        assertThat(meterRegistry.counter("pipe.replay.outcome", "result", "local_hit").count()).isEqualTo(1.0);
        assertThat(meterRegistry.counter("pipe.connections.reconnect").count()).isEqualTo(1.0);
    }

    @Test
    void reconnectFallsBackToKafkaWhenTheLocalBufferCannotProveItHasEverything() throws IOException {
        TaskNotification t9 = notification("t9");
        when(replayBuffer.replaySince(ORG_ID, USER_ID, 8L)).thenReturn(Optional.empty());
        when(kafkaReplayService.replaySince(ORG_ID, USER_ID, 8L))
                .thenReturn(List.of(new ActivityReplayBuffer.Entry(9L, t9, Instant.now())));

        WebSocketNotificationRegistry registry = newRegistry(DEFAULT_OUTBOX_CAPACITY);
        List<WebSocketMessage<?>> messages = new CopyOnWriteArrayList<>();

        registry.register(recordingSession("s1", messages), ORG_ID, USER_ID, 8L);

        await().atMost(2, SECONDS).untilAsserted(() -> assertThat(messages).hasSize(2));
        assertThat(decode(messages.get(0)).get("id")).isEqualTo(9);
        assertThat(decode(messages.get(1)).get("event")).isEqualTo("refresh");
        assertThat(meterRegistry.counter("pipe.replay.outcome", "result", "kafka_hit").count()).isEqualTo(1.0);
    }

    @Test
    void reconnectWithNoHistoryAnywhereStillResumesLiveWithARefresh() throws IOException {
        when(replayBuffer.replaySince(ORG_ID, USER_ID, 100L)).thenReturn(Optional.empty());
        when(kafkaReplayService.replaySince(ORG_ID, USER_ID, 100L)).thenReturn(List.of());

        WebSocketNotificationRegistry registry = newRegistry(DEFAULT_OUTBOX_CAPACITY);
        List<WebSocketMessage<?>> messages = new CopyOnWriteArrayList<>();

        registry.register(recordingSession("s1", messages), ORG_ID, USER_ID, 100L);

        await().atMost(2, SECONDS).untilAsserted(() -> assertThat(messages).hasSize(1));
        assertThat(decode(messages.get(0)).get("event")).isEqualTo("refresh");
        assertThat(meterRegistry.counter("pipe.replay.outcome", "result", "miss").count()).isEqualTo(1.0);
    }

    @Test
    void aConnectionThatCannotKeepUpIsDroppedRatherThanBlockingTheDeliveryOfOtherNotifications() throws Exception {
        WebSocketNotificationRegistry registry = newRegistry(1);
        CountDownLatch enteredSend = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("slow-client");
        doAnswer(invocation -> {
            enteredSend.countDown();
            releaseSend.await();
            return null;
        }).when(session).sendMessage(any());

        registry.register(session, ORG_ID, USER_ID, null);
        registry.activity(notification("a"), 1L); // picked up by the writer, which then blocks "sending" it
        assertThat(enteredSend.await(2, SECONDS)).isTrue();

        registry.activity(notification("b"), 2L); // fills the 1-slot outbox
        registry.activity(notification("c"), 3L); // outbox full -> the connection is dropped

        await().atMost(2, SECONDS).untilAsserted(() -> assertThat(registry.connectionCount(ORG_ID, USER_ID)).isZero());
        verify(session, timeout(2000)).close();
        assertThat(meterRegistry.counter("pipe.connections.dropped").count()).isEqualTo(1.0);
        releaseSend.countDown();
    }

    private WebSocketSession recordingSession(String id, List<WebSocketMessage<?>> received) throws IOException {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        // lenient: some tests using this helper (e.g. plain register/unregister) never actually
        // send a message, which would otherwise fail Mockito's unnecessary-stubbing check.
        lenient().doAnswer(invocation -> {
            received.add(invocation.getArgument(0));
            return null;
        }).when(session).sendMessage(any());
        return session;
    }

    private Map<String, Object> decode(WebSocketMessage<?> message) throws IOException {
        return objectMapper.readValue(((TextMessage) message).getPayload(), new TypeReference<Map<String, Object>>() { });
    }

    private static TaskNotification notification(String taskId) {
        return TaskNotification.newBuilder()
                .setEventId(UUID.randomUUID().toString())
                .setOrgId(ORG_ID)
                .setUserId(USER_ID)
                .setTaskId(taskId)
                .setPushPolicy(PushPolicy.ACTIVITY)
                .build();
    }
}
