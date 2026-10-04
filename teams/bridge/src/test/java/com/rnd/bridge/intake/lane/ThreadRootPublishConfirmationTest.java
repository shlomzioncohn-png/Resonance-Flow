package com.rnd.bridge.intake.lane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * End-to-end regression for the live Slack "root arrives first, then replies" flow: a channel
 * message posted with no thread_ts yet (so it is reconciled as an ordinary non-thread message,
 * landing in its own time-gap GroupState) followed by a reply that names it via thread_ts,
 * migrating a copy of the root into a brand-new thread GroupState (see
 * ChatGroupStateReconciler.newThreadGroupState). This used to leave the thread GroupState's
 * published flag permanently stuck at false, because UnstructuredIntakeLane.afterPublish
 * resolved which group to confirm by reverse-looking up the transcript's first sourceMessageId
 * (always the root, timestamp-sorted) through GroupStateStore's messageId index - an index entry
 * GroupStateStore.writeMessageIndex deliberately never writes for a thread's root (to protect the
 * original non-thread group's ownership of that entry). Every content-changing merge after the
 * first therefore saw published=false and collapsed onto revision 0 forever instead of advancing.
 * afterPublish now reads SourceRecord.groupId() directly (populated from GroupState.groupId() by
 * ChatSourceRecordMapper) instead of reverse-resolving through the index - this test proves the
 * fix through the real lane (only SourceReader/PayloadPublisher are mocked), not just against
 * hand-built GroupStates.
 */
class ThreadRootPublishConfirmationTest {

    // Shared so ObjectStorageClient (reads/index) and ControlPlaneStateStore (the group record's
    // atomic write) see the same data - in production both hit the same MinIO bucket.
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

    private ChatMessage message(String isoTimestamp, String text, String id, String threadId) {
        return new ChatMessage(
                RawSourceType.SLACK, "kamatech", "chat-a", "ruth", Instant.parse(isoTimestamp), text, List.of(), id,
                threadId);
    }

    private IntakeResult publish(List<SourceRecord> records) {
        when(sourceReader.read(any())).thenReturn(records.stream());
        return lane.run(ReadCursor.full(), Instant.now());
    }

    @Test
    void rootThenTwoRepliesConfirmsTheThreadGroupAndAdvancesRevisionOnTheSecondReply() {
        // The root is posted with no thread_ts yet (no reply exists), so it goes through the
        // real-time non-thread path and lands in its own time-gap GroupState, unrelated to the
        // thread group that gets created once the first reply arrives.
        ChatMessage root = message("2026-08-18T09:00:00Z", "root question", "root-ts", null);
        ChatGroupStateReconciler.RealtimeReconcileResult rootResult = reconciler.reconcileNonThreadedMessage(root);
        String originalRootGroupId = rootResult.groupState().groupId();

        // Reply 1 names the root via thread_ts - ChatGroupStateReconciler.newThreadGroupState
        // migrates a copy of the root's snapshot into a brand-new thread GroupState keyed by
        // threadId ("root-ts").
        ChatMessage reply1 = message("2026-08-18T09:01:00Z", "reply one", "reply1-ts", "root-ts");
        List<SourceRecord> reply1Records = reconciler.group(List.of(reply1));
        assertThat(reply1Records).hasSize(1);
        SourceRecord transcript1 = reply1Records.get(0);
        // The explicit groupId this fix relies on - carried from GroupState.groupId(), not
        // derivable from correlationId (Mind's UUID) or the messageId index.
        assertThat(transcript1.groupId()).isEqualTo("root-ts");

        IntakeResult publish1 = publish(reply1Records);
        assertThat(publish1.published()).isEqualTo(1);
        assertThat(publish1.rejected()).isZero();

        // First thread publication: revision 0, and - unlike before this fix - actually
        // confirmed published, since afterPublish resolved the confirmation target from
        // transcript1.groupId() directly instead of reverse-resolving root-ts through the
        // messageId index (which never indexes a thread's root - see
        // GroupStateStore.writeMessageIndex's javadoc).
        GroupState afterReply1 = groupStateStore.load(RawSourceType.SLACK, "kamatech", "root-ts").orElseThrow();
        assertThat(afterReply1.revision()).isZero();
        assertThat(afterReply1.published()).isTrue();

        // The root's messageId index entry must still belong to its ORIGINAL non-thread group -
        // GroupStateStore.writeMessageIndex deliberately never repoints it at the migrated thread
        // group, and this fix must not have changed that: the index stays purely about message
        // ownership/edit lookup, never publication identity.
        assertThat(groupStateStore.findGroupIdForMessage(RawSourceType.SLACK, "kamatech", "root-ts"))
                .contains(originalRootGroupId)
                .isNotEqualTo(Optional.of("root-ts"));

        // Reply 2: a second content-changing merge against the now-confirmed-published thread
        // group must advance to revision 1 - this is exactly the case that used to collapse back
        // onto revision 0 forever, since published() was never actually true.
        ChatMessage reply2 = message("2026-08-18T09:02:00Z", "reply two", "reply2-ts", "root-ts");
        List<SourceRecord> reply2Records = reconciler.group(List.of(reply2));
        assertThat(reply2Records).hasSize(1);
        SourceRecord transcript2 = reply2Records.get(0);
        assertThat(transcript2.groupId()).isEqualTo("root-ts");
        assertThat(transcript2.revision()).isEqualTo(1);
        assertThat(transcript2.body()).contains("root question").contains("reply one").contains("reply two");

        IntakeResult publish2 = publish(reply2Records);
        assertThat(publish2.published()).isEqualTo(1);
        assertThat(publish2.rejected()).isZero();

        GroupState afterReply2 = groupStateStore.load(RawSourceType.SLACK, "kamatech", "root-ts").orElseThrow();
        assertThat(afterReply2.revision()).isEqualTo(1);
        assertThat(afterReply2.published()).isTrue();
    }
}
