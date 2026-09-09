package com.rnd.bridge.intake.lane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rnd.bridge.core.IntakeResult;
import com.rnd.bridge.core.PayloadMapper;
import com.rnd.bridge.core.PayloadPublisher;
import com.rnd.bridge.core.PayloadValidator;
import com.rnd.bridge.core.ReadCursor;
import com.rnd.bridge.core.SourceReader;
import com.rnd.bridge.core.error.LaneException;
import com.rnd.bridge.intake.config.ChatSessionProperties;
import com.rnd.bridge.intake.model.ChatMessage;
import com.rnd.bridge.intake.model.GroupState;
import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.intake.service.ChatGroupStateReconciler;
import com.rnd.bridge.intake.service.ChatSessionGrouper;
import com.rnd.bridge.intake.service.ChatSourceRecordMapper;
import com.rnd.bridge.intake.service.IntakeLandingService;
import com.rnd.bridge.raw.model.RawObjectEvent;
import com.rnd.bridge.raw.model.RawSourceType;
import com.rnd.bridge.raw.model.ScopeDecision;
import com.rnd.bridge.storage.ControlPlaneStateStores;
import com.rnd.bridge.storage.InMemoryObjectStorageClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Exercises the two-phase publish confirmation end to end through a real
 * ChatGroupStateReconciler + GroupStateStore wired into a real UnstructuredIntakeLane: a chat
 * group is only ever marked published once its transcript record has actually gone through
 * the lane's publish step successfully (afterPublish -> GroupStateStore.confirmPublished), not
 * merely because the reconciler built a record for it.
 * Failure stubs use same() rather than the default equals-based matching: RawObjectEvent has
 * no byte[] fields, so a retry's rebuilt event is .equals() to the original failed one
 * (identical content) - equals-based stubbing would make run1's failure stub also swallow
 * run2's otherwise-successful retry of that exact same content.
 */
class ChatGroupPublishConfirmationTest {

    // Shared so ObjectStorageClient (reads/index) and ControlPlaneStateStore (the group
    // record's atomic write) see the same data - in production both hit the same MinIO bucket.
    private final Map<String, byte[]> backingStore = new ConcurrentHashMap<>();
    private final InMemoryObjectStorageClient storageClient = new InMemoryObjectStorageClient(backingStore);
    private final GroupStateStore groupStateStore = new GroupStateStore(
            storageClient, ControlPlaneStateStores.inMemory(backingStore),
            new ObjectMapper().registerModule(new JavaTimeModule()));
    private final ChatSessionProperties properties = new ChatSessionProperties(45);
    private final ChatGroupStateReconciler reconciler = new ChatGroupStateReconciler(
            properties, new ChatSessionGrouper(properties), new ChatSourceRecordMapper(), groupStateStore);

    @SuppressWarnings("unchecked")
    private final SourceReader<SourceRecord> sourceReader = mock(SourceReader.class);
    @SuppressWarnings("unchecked")
    private final PayloadMapper<SourceRecord, RawObjectEvent> payloadMapper = mock(PayloadMapper.class);
    @SuppressWarnings("unchecked")
    private final PayloadValidator<RawObjectEvent> payloadValidator = mock(PayloadValidator.class);
    @SuppressWarnings("unchecked")
    private final PayloadPublisher<RawObjectEvent> payloadPublisher = mock(PayloadPublisher.class);
    private final IntakeLandingService landingService = mock(IntakeLandingService.class);

    private final UnstructuredIntakeLane lane = new UnstructuredIntakeLane(
            RawSourceType.SLACK, sourceReader, payloadMapper, payloadValidator, payloadPublisher, landingService,
            groupStateStore);

    private ChatMessage messageWithAttachment(String isoTimestamp, String text, String id) {
        ChatMessage.Attachment attachment =
                new ChatMessage.Attachment("note.txt", "text/plain", "fake-bytes".getBytes(StandardCharsets.UTF_8));
        return new ChatMessage(
                RawSourceType.SLACK, "kamatech", "chat-a", "ruth", Instant.parse(isoTimestamp), text,
                List.of(attachment), id, null);
    }

    private RawObjectEvent eventFor(SourceRecord record) {
        return RawObjectEvent.create(
                "SLACK/kamatech/2026-08-18/" + record.naturalId() + ".json", record.sourceType(), record.tenantId(),
                Instant.parse("2026-08-18T09:00:00Z"), ScopeDecision.INCLUDED, "sha256:" + "0".repeat(64), null,
                record.participants(), "application/json", 2L, record.occurredAt(), record.correlationId(),
                record.attachmentIndex());
    }

    private SourceRecord transcriptOf(List<SourceRecord> records) {
        return records.stream().filter(r -> r.attachmentIndex() == 0).findFirst().orElseThrow();
    }

    private SourceRecord attachmentOf(List<SourceRecord> records) {
        return records.stream().filter(r -> r.attachmentIndex() > 0).findFirst().orElseThrow();
    }

    @Test
    void transcriptFailsAttachmentSucceedsThenRetrySucceeds() {
        List<ChatMessage> messages = List.of(messageWithAttachment("2026-08-18T09:00:00Z", "listen to this", "m1"));
        List<SourceRecord> run1Records = reconciler.group(messages);
        SourceRecord transcript = transcriptOf(run1Records);
        SourceRecord attachment = attachmentOf(run1Records);
        RawObjectEvent transcriptEvent = eventFor(transcript);
        RawObjectEvent attachmentEvent = eventFor(attachment);

        when(sourceReader.read(any())).thenReturn(Stream.of(transcript, attachment));
        when(payloadMapper.map(eq(transcript), any())).thenReturn(transcriptEvent);
        when(payloadMapper.map(eq(attachment), any())).thenReturn(attachmentEvent);
        doThrow(new LaneException("kafka down")).when(payloadPublisher).publish(same(transcriptEvent));

        IntakeResult run1 = lane.run(ReadCursor.full(), Instant.parse("2026-08-18T10:00:00Z"));

        assertThat(run1.published()).isEqualTo(1); // attachment only
        assertThat(run1.rejected()).isEqualTo(1); // transcript
        // transcript.correlationId() is Mind's UUID, not GroupStateStore's own key - resolve the
        // group the same way UnstructuredIntakeLane.afterPublish does, via the messageId index.
        String groupId = groupStateStore.findGroupIdForMessage(RawSourceType.SLACK, "kamatech", "m1").orElseThrow();
        GroupState afterRun1 = groupStateStore.load(RawSourceType.SLACK, "kamatech", groupId).orElseThrow();
        assertThat(afterRun1.published()).isFalse();

        // Retry: cursor was frozen (rejected > 0), so the same messages are reconciled again.
        List<SourceRecord> run2Records = reconciler.group(messages);
        assertThat(run2Records).hasSize(2); // still-pending group: full rebuild, not the attachment-only carve-out
        SourceRecord retryTranscript = transcriptOf(run2Records);
        SourceRecord retryAttachment = attachmentOf(run2Records);
        RawObjectEvent retryTranscriptEvent = eventFor(retryTranscript);
        RawObjectEvent retryAttachmentEvent = eventFor(retryAttachment);
        when(sourceReader.read(any())).thenReturn(Stream.of(retryTranscript, retryAttachment));
        when(payloadMapper.map(eq(retryTranscript), any())).thenReturn(retryTranscriptEvent);
        when(payloadMapper.map(eq(retryAttachment), any())).thenReturn(retryAttachmentEvent);

        IntakeResult run2 = lane.run(ReadCursor.full(), Instant.parse("2026-08-18T10:05:00Z"));

        assertThat(run2.published()).isEqualTo(2);
        assertThat(run2.rejected()).isEqualTo(0);
        GroupState afterRun2 = groupStateStore.load(RawSourceType.SLACK, "kamatech", groupId).orElseThrow();
        assertThat(afterRun2.published()).isTrue();
        assertThat(afterRun2.revision()).isZero(); // a retry is not a content change
        assertThat(afterRun2.correlationId()).isEqualTo(transcript.correlationId()); // retry keeps the same id
    }

    @Test
    void transcriptSucceedsAttachmentFailsThenRetrySucceeds() {
        List<ChatMessage> messages = List.of(messageWithAttachment("2026-08-18T09:00:00Z", "listen to this", "m1"));
        List<SourceRecord> run1Records = reconciler.group(messages);
        SourceRecord transcript = transcriptOf(run1Records);
        SourceRecord attachment = attachmentOf(run1Records);
        RawObjectEvent transcriptEvent = eventFor(transcript);
        RawObjectEvent attachmentEvent = eventFor(attachment);

        when(sourceReader.read(any())).thenReturn(Stream.of(transcript, attachment));
        when(payloadMapper.map(eq(transcript), any())).thenReturn(transcriptEvent);
        when(payloadMapper.map(eq(attachment), any())).thenReturn(attachmentEvent);
        doThrow(new LaneException("kafka down")).when(payloadPublisher).publish(same(attachmentEvent));

        IntakeResult run1 = lane.run(ReadCursor.full(), Instant.parse("2026-08-18T10:00:00Z"));

        assertThat(run1.published()).isEqualTo(1); // transcript only
        assertThat(run1.rejected()).isEqualTo(1); // attachment
        // transcript.correlationId() is Mind's UUID, not GroupStateStore's own key - resolve the
        // group the same way UnstructuredIntakeLane.afterPublish does, via the messageId index.
        String groupId = groupStateStore.findGroupIdForMessage(RawSourceType.SLACK, "kamatech", "m1").orElseThrow();
        GroupState afterRun1 = groupStateStore.load(RawSourceType.SLACK, "kamatech", groupId).orElseThrow();
        // The transcript was confirmed despite its sibling attachment failing independently.
        assertThat(afterRun1.published()).isTrue();

        // Retry: cursor was frozen (rejected > 0), so the same messages are reconciled again.
        List<SourceRecord> run2Records = reconciler.group(messages);
        // Confirmed+unchanged group: only the attachment is regenerated, not the transcript.
        assertThat(run2Records).hasSize(1);
        SourceRecord retryAttachment = run2Records.get(0);
        assertThat(retryAttachment.attachmentIndex()).isGreaterThan(0);
        RawObjectEvent retryAttachmentEvent = eventFor(retryAttachment);
        when(sourceReader.read(any())).thenReturn(Stream.of(retryAttachment));
        when(payloadMapper.map(eq(retryAttachment), any())).thenReturn(retryAttachmentEvent);

        IntakeResult run2 = lane.run(ReadCursor.full(), Instant.parse("2026-08-18T10:05:00Z"));

        assertThat(run2.published()).isEqualTo(1);
        assertThat(run2.rejected()).isEqualTo(0);
        GroupState afterRun2 = groupStateStore.load(RawSourceType.SLACK, "kamatech", groupId).orElseThrow();
        assertThat(afterRun2.published()).isTrue();
        assertThat(afterRun2.revision()).isZero();
        assertThat(afterRun2.correlationId()).isEqualTo(transcript.correlationId()); // retry keeps the same id
    }
}
