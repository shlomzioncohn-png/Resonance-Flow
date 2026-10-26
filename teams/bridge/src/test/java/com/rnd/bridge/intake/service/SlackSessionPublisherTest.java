package com.rnd.bridge.intake.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rnd.bridge.core.PayloadPublisher;
import com.rnd.bridge.intake.config.ChatSessionProperties;
import com.rnd.bridge.intake.lane.GroupStateStore;
import com.rnd.bridge.intake.model.ChatMessage;
import com.rnd.bridge.intake.model.GroupState;
import com.rnd.bridge.raw.RawObjectValidator;
import com.rnd.bridge.raw.model.RawObjectEvent;
import com.rnd.bridge.raw.model.RawObjectRef;
import com.rnd.bridge.raw.model.RawSourceType;
import com.rnd.bridge.raw.model.Role;
import com.rnd.bridge.scoping.AllowlistScopingFilter;
import com.rnd.bridge.storage.ControlPlaneStateStores;
import com.rnd.bridge.storage.InMemoryObjectStorageClient;
import com.rnd.bridge.storage.ObjectKeyBuilder;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * End-to-end: a real SlackSessionPublisher wired against a real ChatGroupStateReconciler +
 * GroupStateStore + IntakeLandingService (over an in-memory ObjectStorageClient), proving a
 * closed non-thread session publishes exactly one RawObjectEvent containing the transcript plus
 * every attachment landed during the session, then confirms the group published - never a
 * partial object list. Only the Kafka send itself (PayloadPublisher) is mocked, since this is
 * about the assembly/confirmation sequence, not transport.
 */
class SlackSessionPublisherTest {

    private final Map<String, byte[]> backingStore = new ConcurrentHashMap<>();
    private final InMemoryObjectStorageClient storageClient = new InMemoryObjectStorageClient(backingStore);
    private final GroupStateStore groupStateStore = new GroupStateStore(
            storageClient, ControlPlaneStateStores.inMemory(backingStore),
            new ObjectMapper().registerModule(new JavaTimeModule()));
    private final ChatSessionProperties properties = new ChatSessionProperties(45);
    private final ChatSourceRecordMapper chatSourceRecordMapper = new ChatSourceRecordMapper();
    private final ChatGroupStateReconciler reconciler = new ChatGroupStateReconciler(
            properties, new ChatSessionGrouper(properties), chatSourceRecordMapper, groupStateStore);
    private final SourceRecordRefMapper refMapper =
            new SourceRecordRefMapper(new ObjectKeyBuilder(), new IntakeLandingService(storageClient));

    @SuppressWarnings("unchecked")
    private final PayloadPublisher<RawObjectEvent> kafkaPublisher = mock(PayloadPublisher.class);

    private final SlackSessionPublisher sessionPublisher = new SlackSessionPublisher(
            new AllowlistScopingFilter(), refMapper, new IntakeLandingService(storageClient),
            new RawObjectValidator(), kafkaPublisher, groupStateStore, chatSourceRecordMapper);

    @Test
    void publishesTranscriptOnlyWhenNoAttachmentsWereLandedDuringTheSession() {
        ChatMessage message = new ChatMessage(
                RawSourceType.SLACK, "00000000-0000-0000-0000-000000000001", "chat-a", "ruth", Instant.parse("2026-08-18T09:00:00Z"), "hi",
                List.of(), "m1", null);
        ChatGroupStateReconciler.RealtimeReconcileResult result = reconciler.reconcileNonThreadedMessage(message);
        GroupState state = result.groupState();

        sessionPublisher.publishSession(state, Instant.parse("2026-08-18T09:45:00Z"));

        ArgumentCaptor<RawObjectEvent> captor = ArgumentCaptor.forClass(RawObjectEvent.class);
        org.mockito.Mockito.verify(kafkaPublisher).publish(captor.capture());
        assertThat(captor.getValue().objects()).hasSize(1);
        assertThat(captor.getValue().objects().get(0).role()).isEqualTo(Role.TRANSCRIPT);
        assertThat(captor.getValue().correlationId()).isEqualTo(state.correlationId());

        GroupState afterPublish = groupStateStore.load(RawSourceType.SLACK, "00000000-0000-0000-0000-000000000001", state.groupId()).orElseThrow();
        assertThat(afterPublish.published()).isTrue();
    }

    @Test
    void publishesTranscriptPlusEveryAttachmentLandedDuringTheSessionInOneEvent() {
        ChatMessage.Attachment attachment =
                new ChatMessage.Attachment("F1", "note.txt", "text/plain", "fake-bytes".getBytes());
        ChatMessage message = new ChatMessage(
                RawSourceType.SLACK, "00000000-0000-0000-0000-000000000001", "chat-a", "ruth", Instant.parse("2026-08-18T09:00:00Z"),
                "listen to this", List.of(attachment), "m1", null);
        ChatGroupStateReconciler.RealtimeReconcileResult result = reconciler.reconcileNonThreadedMessage(message);
        assertThat(result.newAttachmentRecords()).hasSize(1);

        // Simulates SlackEventsController.landAttachments landing the attachment during the
        // session, before the timer ever fires.
        RawObjectRef attachmentRef = refMapper.toRef(
                result.newAttachmentRecords().get(0), Instant.parse("2026-08-18T09:00:00Z"));
        groupStateStore.recordLandedAttachment(
                RawSourceType.SLACK, "00000000-0000-0000-0000-000000000001", result.groupState().groupId(), "m1", "F1", attachmentRef);
        GroupState stateWithLandedAttachment =
                groupStateStore.load(RawSourceType.SLACK, "00000000-0000-0000-0000-000000000001", result.groupState().groupId()).orElseThrow();

        sessionPublisher.publishSession(stateWithLandedAttachment, Instant.parse("2026-08-18T09:45:00Z"));

        ArgumentCaptor<RawObjectEvent> captor = ArgumentCaptor.forClass(RawObjectEvent.class);
        org.mockito.Mockito.verify(kafkaPublisher).publish(captor.capture());
        assertThat(captor.getValue().objects()).hasSize(2);
        assertThat(captor.getValue().objects().get(0).role()).isEqualTo(Role.TRANSCRIPT);
        assertThat(captor.getValue().objects().get(1).role()).isEqualTo(Role.ATTACHMENT);

        GroupState afterPublish = groupStateStore.load(RawSourceType.SLACK, "00000000-0000-0000-0000-000000000001", stateWithLandedAttachment.groupId())
                .orElseThrow();
        assertThat(afterPublish.published()).isTrue();
    }

    @Test
    void neverConfirmsPublishedWhenKafkaPublishFails() {
        ChatMessage message = new ChatMessage(
                RawSourceType.SLACK, "00000000-0000-0000-0000-000000000001", "chat-a", "ruth", Instant.parse("2026-08-18T09:00:00Z"), "hi",
                List.of(), "m1", null);
        ChatGroupStateReconciler.RealtimeReconcileResult result = reconciler.reconcileNonThreadedMessage(message);
        doAnswer(invocation -> {
            throw new RuntimeException("kafka down");
        }).when(kafkaPublisher).publish(any());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> sessionPublisher.publishSession(result.groupState(), Instant.parse("2026-08-18T09:45:00Z")))
                .isInstanceOf(RuntimeException.class);

        GroupState state = groupStateStore.load(RawSourceType.SLACK, "00000000-0000-0000-0000-000000000001", result.groupState().groupId())
                .orElseThrow();
        assertThat(state.published()).isFalse();
    }
}
