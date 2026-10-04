package com.rnd.bridge.intake.lane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rnd.bridge.core.IntakeResult;
import com.rnd.bridge.core.PayloadPublisher;
import com.rnd.bridge.core.PayloadValidator;
import com.rnd.bridge.core.ReadCursor;
import com.rnd.bridge.core.SourceReader;
import com.rnd.bridge.intake.config.ChatSessionProperties;
import com.rnd.bridge.intake.model.ChatMessage;
import com.rnd.bridge.intake.model.GroupState;
import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.intake.service.ChatGroupStateReconciler;
import com.rnd.bridge.intake.service.ChatSessionGrouper;
import com.rnd.bridge.intake.service.ChatSourceRecordMapper;
import com.rnd.bridge.intake.service.IntakeLandingService;
import com.rnd.bridge.raw.model.ChangeType;
import com.rnd.bridge.raw.model.RawObjectEvent;
import com.rnd.bridge.raw.model.RawSourceType;
import com.rnd.bridge.scoping.AllowlistScopingFilter;
import com.rnd.bridge.storage.ControlPlaneStateStores;
import com.rnd.bridge.storage.InMemoryObjectStorageClient;
import com.rnd.bridge.storage.ObjectKeyBuilder;
import com.rnd.bridge.storage.ObjectStorageClient;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * End-to-end proof of the Slack edit path: real ChatGroupStateReconciler + GroupStateStore +
 * UnstructuredPayloadMapper wired into a real UnstructuredIntakeLane (only SourceReader and
 * PayloadPublisher are mocked - there's no real Kafka/MinIO in a unit test). A Slack message
 * edit must resolve to the same group purely via the messageId ("ts") -> groupId reverse index
 * (see ChatGroupStateReconciler.reconcileTimeGap), never via any new identity; preserve the
 * group's correlationId; bump its revision; rebuild the full transcript (not a diff); and reach
 * the wire as changeType UPSERT — while a stale/redelivered ack for the pre-edit revision must
 * never be able to mark the edited, still-unconfirmed revision as published (see
 * GroupStateStore.confirmPublished's expectedRevision gate).
 */
class ChatEditWireContractTest {

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
    private final UnstructuredPayloadMapper mapper = new UnstructuredPayloadMapper(
            new AllowlistScopingFilter(), new ObjectKeyBuilder(),
            new IntakeLandingService(mock(ObjectStorageClient.class)));

    @SuppressWarnings("unchecked")
    private final SourceReader<SourceRecord> sourceReader = mock(SourceReader.class);
    @SuppressWarnings("unchecked")
    private final PayloadValidator<RawObjectEvent> payloadValidator = mock(PayloadValidator.class);
    @SuppressWarnings("unchecked")
    private final PayloadPublisher<RawObjectEvent> payloadPublisher = mock(PayloadPublisher.class);
    private final IntakeLandingService landingService = mock(IntakeLandingService.class);
    private final CalendarEventStateStore calendarEventStateStore = mock(CalendarEventStateStore.class);

    private final UnstructuredIntakeLane lane = new UnstructuredIntakeLane(
            RawSourceType.SLACK, sourceReader, mapper, payloadValidator, payloadPublisher, landingService,
            groupStateStore, calendarEventStateStore);

    private ChatMessage message(String isoTimestamp, String text, String id) {
        return new ChatMessage(
                RawSourceType.SLACK, "kamatech", "chat-a", "ruth", Instant.parse(isoTimestamp), text, List.of(), id,
                null);
    }

    @Test
    void slackEditKeepsCorrelationIdBumpsRevisionRebuildsFullTranscriptAndStaysUpsert() {
        // Run 1: the original message, read/reconciled/mapped/published through the real lane -
        // proves the happy path actually reaches Kafka as revision 0.
        List<SourceRecord> run1Records =
                reconciler.group(List.of(message("2026-08-18T09:00:00Z", "original message", "m1")));
        assertThat(run1Records).hasSize(1);
        when(sourceReader.read(any())).thenReturn(Stream.of(run1Records.get(0)));

        IntakeResult run1 = lane.run(ReadCursor.full(), Instant.parse("2026-08-18T10:00:00Z"));

        assertThat(run1.published()).isEqualTo(1);
        ArgumentCaptor<RawObjectEvent> captor = ArgumentCaptor.forClass(RawObjectEvent.class);
        verify(payloadPublisher).publish(captor.capture());
        RawObjectEvent firstEvent = captor.getValue();
        assertThat(firstEvent.revision()).isZero();
        assertThat(firstEvent.changeType()).isEqualTo(ChangeType.UPSERT);

        // record.correlationId() is Mind's UUID, not GroupStateStore's own key - resolve the
        // group the same way UnstructuredIntakeLane.afterPublish does, via the messageId index.
        String groupId = groupStateStore.findGroupIdForMessage(RawSourceType.SLACK, "kamatech", "m1").orElseThrow();
        assertThat(groupStateStore.load(RawSourceType.SLACK, "kamatech", groupId).orElseThrow().published()).isTrue();

        // Run 2: the same message, edited - recognized as an edit purely via the messageId
        // ("m1") -> groupId reverse index, not any new identity. Reconciled directly (not
        // through the lane) so the group is left in the same "reconciled but not yet
        // Kafka-confirmed" state a real run would leave it in before its publish step runs -
        // needed below to prove the stale-ack case means something.
        List<SourceRecord> run2Records =
                reconciler.group(List.of(message("2026-08-18T09:00:00Z", "updated message", "m1")));
        assertThat(run2Records).hasSize(1);
        SourceRecord edited = run2Records.get(0);

        // Same logical group, not a new one.
        assertThat(groupStateStore.findGroupIdForMessage(RawSourceType.SLACK, "kamatech", "m1")).contains(groupId);
        // Full transcript rebuilt from scratch, not a diff - the edited text replaces the old
        // snapshot for "m1" (see ChatGroupStateReconciler.mergeSnapshots), it isn't appended
        // alongside it.
        assertThat(edited.body()).contains("updated message");
        assertThat(edited.body()).doesNotContain("original message");

        RawObjectEvent editedEvent = mapper.map(edited, Instant.parse("2026-08-18T10:05:00Z"));
        assertThat(editedEvent.correlationId()).isEqualTo(firstEvent.correlationId());
        assertThat(editedEvent.revision()).isEqualTo(1);
        assertThat(editedEvent.changeType()).isEqualTo(ChangeType.UPSERT);

        GroupState afterEdit = groupStateStore.load(RawSourceType.SLACK, "kamatech", groupId).orElseThrow();
        assertThat(afterEdit.revision()).isEqualTo(1);
        assertThat(afterEdit.published()).isFalse(); // not yet confirmed by an actual Kafka ack

        // A stale/redelivered ack for revision 0 (run1's, already confirmed once above) arrives
        // after the edit has already advanced the group to revision 1 - it must not be able to
        // mark the still-unconfirmed edited revision as published.
        groupStateStore.confirmPublished(RawSourceType.SLACK, "kamatech", groupId, 0);

        GroupState afterStaleAck = groupStateStore.load(RawSourceType.SLACK, "kamatech", groupId).orElseThrow();
        assertThat(afterStaleAck.revision()).isEqualTo(1);
        assertThat(afterStaleAck.published()).isFalse();
    }
}
