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
import com.rnd.bridge.intake.parser.SourceRecordGrouper;
import com.rnd.bridge.intake.service.ChatGroupStateReconciler;
import com.rnd.bridge.intake.service.ChatSessionGrouper;
import com.rnd.bridge.intake.service.ChatSourceRecordMapper;
import com.rnd.bridge.intake.service.IntakeLandingService;
import com.rnd.bridge.raw.model.ChangeType;
import com.rnd.bridge.raw.model.Modality;
import com.rnd.bridge.raw.model.RawObjectEvent;
import com.rnd.bridge.raw.model.RawObjectRef;
import com.rnd.bridge.raw.model.RawSourceType;
import com.rnd.bridge.raw.model.Role;
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
 * group is only ever marked published once its whole object group (transcript + every
 * attachment, bundled into one RawObjectEvent - see SourceRecordGrouper) has actually gone
 * through the lane's land/publish steps successfully (afterPublish -> confirmPublished), not
 * merely because the reconciler built records for it. Publication is atomic per group now - a
 * transcript-only or attachment-only partial outcome no longer exists (see
 * ChatGroupStateReconciler.mergeAndMaybePublish's simplification): either the whole group's
 * event publishes, or none of it does, and a retry rebuilds and republishes the complete group.
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
    private final SourceReader<List<SourceRecord>> sourceReader = mock(SourceReader.class);
    @SuppressWarnings("unchecked")
    private final PayloadMapper<List<SourceRecord>, RawObjectEvent> payloadMapper = mock(PayloadMapper.class);
    @SuppressWarnings("unchecked")
    private final PayloadValidator<RawObjectEvent> payloadValidator = mock(PayloadValidator.class);
    @SuppressWarnings("unchecked")
    private final PayloadPublisher<RawObjectEvent> payloadPublisher = mock(PayloadPublisher.class);
    private final IntakeLandingService landingService = mock(IntakeLandingService.class);
    private final CalendarEventStateStore calendarEventStateStore = mock(CalendarEventStateStore.class);

    private final UnstructuredIntakeLane lane = new UnstructuredIntakeLane(
            RawSourceType.SLACK, sourceReader, payloadMapper, payloadValidator, payloadPublisher, landingService,
            groupStateStore, calendarEventStateStore);

    private ChatMessage messageWithAttachment(String isoTimestamp, String text, String id) {
        ChatMessage.Attachment attachment =
                new ChatMessage.Attachment("note.txt", "text/plain", "fake-bytes".getBytes(StandardCharsets.UTF_8));
        return new ChatMessage(
                RawSourceType.SLACK, "kamatech", "chat-a", "ruth", Instant.parse(isoTimestamp), text,
                List.of(attachment), id, null);
    }

    private RawObjectEvent eventFor(List<SourceRecord> group) {
        SourceRecord primary = UnstructuredPayloadMapper.primaryOf(group);
        List<RawObjectRef> objects = group.stream()
                .map(record -> new RawObjectRef(
                        record.attachmentIndex() == 0 ? Role.TRANSCRIPT : Role.ATTACHMENT,
                        "SLACK/kamatech/2026-08-18/" + record.naturalId() + ".json",
                        "sha256:" + "0".repeat(64), "application/json", 2L, Modality.TEXT))
                .toList();
        return RawObjectEvent.create(
                primary.sourceType(), primary.tenantId(), Instant.parse("2026-08-18T09:00:00Z"),
                ScopeDecision.INCLUDED, primary.participants(), primary.occurredAt(), primary.correlationId(),
                primary.revision(), ChangeType.UPSERT, objects);
    }

    @Test
    void wholeGroupFailsThenRetrySucceedsAndPublishesTranscriptAndAttachmentTogether() {
        List<ChatMessage> messages = List.of(messageWithAttachment("2026-08-18T09:00:00Z", "listen to this", "m1"));
        List<SourceRecord> run1Records = reconciler.group(messages);
        assertThat(run1Records).hasSize(2); // transcript + attachment, one logical group
        List<List<SourceRecord>> run1Groups = SourceRecordGrouper.groupByCorrelationId(run1Records);
        assertThat(run1Groups).hasSize(1);
        RawObjectEvent event1 = eventFor(run1Groups.get(0));

        when(sourceReader.read(any())).thenReturn(Stream.of(run1Groups.get(0)));
        when(payloadMapper.map(eq(run1Groups.get(0)), any())).thenReturn(event1);
        // same() rather than equals-based matching: RawObjectEvent has no byte[] fields, so a
        // retry's rebuilt event is .equals() to the original failed one (identical content) -
        // equals-based stubbing would make run1's failure stub also swallow run2's otherwise-
        // successful retry of that exact same content.
        doThrow(new LaneException("kafka down")).when(payloadPublisher).publish(same(event1));

        IntakeResult run1 = lane.run(ReadCursor.full(), Instant.parse("2026-08-18T10:00:00Z"));

        assertThat(run1.published()).isZero();
        assertThat(run1.rejected()).isEqualTo(1); // the whole group, not a per-attachment count
        String groupId = groupStateStore.findGroupIdForMessage(RawSourceType.SLACK, "kamatech", "m1").orElseThrow();
        GroupState afterRun1 = groupStateStore.load(RawSourceType.SLACK, "kamatech", groupId).orElseThrow();
        assertThat(afterRun1.published()).isFalse();

        // Retry: cursor was frozen (rejected > 0), so the same messages are reconciled again.
        List<SourceRecord> run2Records = reconciler.group(messages);
        assertThat(run2Records).hasSize(2); // still-pending group: full rebuild, not a partial carve-out
        List<List<SourceRecord>> run2Groups = SourceRecordGrouper.groupByCorrelationId(run2Records);
        RawObjectEvent event2 = eventFor(run2Groups.get(0));
        when(sourceReader.read(any())).thenReturn(Stream.of(run2Groups.get(0)));
        when(payloadMapper.map(eq(run2Groups.get(0)), any())).thenReturn(event2);

        IntakeResult run2 = lane.run(ReadCursor.full(), Instant.parse("2026-08-18T10:05:00Z"));

        assertThat(run2.published()).isEqualTo(1); // one group, one event
        assertThat(run2.rejected()).isEqualTo(0);
        GroupState afterRun2 = groupStateStore.load(RawSourceType.SLACK, "kamatech", groupId).orElseThrow();
        assertThat(afterRun2.published()).isTrue();
        assertThat(afterRun2.revision()).isZero(); // a retry is not a content change
    }

    @Test
    void successfulPublishLandsEveryRecordInTheGroupBeforePublishing() {
        List<ChatMessage> messages = List.of(messageWithAttachment("2026-08-18T09:00:00Z", "listen to this", "m1"));
        List<SourceRecord> records = reconciler.group(messages);
        List<List<SourceRecord>> groups = SourceRecordGrouper.groupByCorrelationId(records);
        RawObjectEvent event = eventFor(groups.get(0));

        when(sourceReader.read(any())).thenReturn(Stream.of(groups.get(0)));
        when(payloadMapper.map(eq(groups.get(0)), any())).thenReturn(event);

        IntakeResult result = lane.run(ReadCursor.full(), Instant.parse("2026-08-18T10:00:00Z"));

        assertThat(result.published()).isEqualTo(1);
        // Both the transcript and the attachment were landed, in the same group's afterMap.
        org.mockito.Mockito.verify(landingService, org.mockito.Mockito.times(2))
                .land(any(), any(), any(), any());
        String groupId = groupStateStore.findGroupIdForMessage(RawSourceType.SLACK, "kamatech", "m1").orElseThrow();
        GroupState state = groupStateStore.load(RawSourceType.SLACK, "kamatech", groupId).orElseThrow();
        assertThat(state.published()).isTrue();
    }
}
