package com.rnd.bridge.intake.lane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rnd.bridge.core.PayloadPublisher;
import com.rnd.bridge.core.SourceReader;
import com.rnd.bridge.intake.config.ChatSessionProperties;
import com.rnd.bridge.intake.model.ChatMessage;
import com.rnd.bridge.intake.model.GroupState;
import com.rnd.bridge.intake.model.MessageSnapshot;
import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.intake.parser.SourceRecordGrouper;
import com.rnd.bridge.intake.service.ChatGroupStateReconciler;
import com.rnd.bridge.intake.service.ChatSessionGrouper;
import com.rnd.bridge.intake.service.ChatSourceRecordMapper;
import com.rnd.bridge.intake.service.IntakeLandingService;
import com.rnd.bridge.intake.service.SourceRecordRefMapper;
import com.rnd.bridge.raw.RawObjectValidator;
import com.rnd.bridge.raw.model.RawObjectEvent;
import com.rnd.bridge.raw.model.RawSourceType;
import com.rnd.bridge.scoping.AllowlistScopingFilter;
import com.rnd.bridge.storage.ControlPlaneStateStores;
import com.rnd.bridge.storage.InMemoryObjectStorageClient;
import com.rnd.bridge.storage.ObjectKeyBuilder;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/**
 * Reproduces the Theory B bug (a thread root's own message_changed payload carries
 * {@code thread_ts == ts} once it has replies) directly against the real
 * {@link ChatGroupStateReconciler} + {@link GroupStateStore} (same style as
 * ThreadedSlackAttachmentRetentionTest), bypassing SlackEventsController's HTTP/JSON layer.
 * Every ChatMessage built here with {@code threadId == null} for a root edit is exactly what
 * SlackEventsController.withoutSelfThreadId now produces before calling
 * reconciler.reconcileNonThreadedMessage - this class proves what that call actually does once
 * it lands, not how the controller gets there (see SlackEventsControllerTest's
 * rootMessageChangedWithSelfThreadIdStrips... tests for that part).
 */
class ThreadRootEditChannelGroupTest {

    private static final String TENANT_ID = "kamatech";
    private static final String CHAT_ID = "C1";
    private static final String ROOT_ID = "2000.100";
    private static final String REPLY_ID = "2000.200";

    private final Map<String, byte[]> backingStore = new ConcurrentHashMap<>();
    private final InMemoryObjectStorageClient storageClient = new InMemoryObjectStorageClient(backingStore);
    private final GroupStateStore groupStateStore = new GroupStateStore(
            storageClient, ControlPlaneStateStores.inMemory(backingStore),
            new ObjectMapper().registerModule(new JavaTimeModule()));
    private final ChatSessionProperties properties = new ChatSessionProperties(45);
    private final ChatSourceRecordMapper chatSourceRecordMapper = new ChatSourceRecordMapper();
    private final ChatGroupStateReconciler reconciler = new ChatGroupStateReconciler(
            properties, new ChatSessionGrouper(properties), chatSourceRecordMapper, groupStateStore);
    private final IntakeLandingService landingService = new IntakeLandingService(storageClient);
    private final SourceRecordRefMapper refMapper = new SourceRecordRefMapper(new ObjectKeyBuilder(), landingService);
    private final UnstructuredPayloadMapper payloadMapper =
            new UnstructuredPayloadMapper(new AllowlistScopingFilter(), refMapper);
    private final RawObjectValidator validator = new RawObjectValidator();

    @SuppressWarnings("unchecked")
    private final PayloadPublisher<RawObjectEvent> kafkaPublisher = mock(PayloadPublisher.class);
    @SuppressWarnings("unchecked")
    private final SourceReader<List<SourceRecord>> sourceReader = mock(SourceReader.class);
    private final CalendarEventStateStore calendarEventStateStore = mock(CalendarEventStateStore.class);

    private final UnstructuredIntakeLane lane = new UnstructuredIntakeLane(
            RawSourceType.SLACK, sourceReader, payloadMapper, validator, kafkaPublisher, landingService,
            groupStateStore, calendarEventStateStore);

    private ChatMessage rootMessage(String text) {
        return new ChatMessage(
                RawSourceType.SLACK, TENANT_ID, CHAT_ID, "ruth", Instant.parse("2026-08-18T09:00:00Z"), text,
                List.of(), ROOT_ID, null);
    }

    private ChatMessage replyMessage() {
        return new ChatMessage(
                RawSourceType.SLACK, TENANT_ID, CHAT_ID, "malki", Instant.parse("2026-08-18T09:05:00Z"),
                "reply text", List.of(), REPLY_ID, ROOT_ID);
    }

    /** Publishes group via the real lane, exactly as SlackEventsController.publishGroups does. */
    private void publish(List<SourceRecord> records) {
        if (records.isEmpty()) {
            return;
        }
        lane.run(SourceRecordGrouper.groupByCorrelationId(records).stream(), Instant.now());
    }

    private String seedRootAndReply() {
        ChatGroupStateReconciler.RealtimeReconcileResult rootResult =
                reconciler.reconcileNonThreadedMessage(rootMessage("root text"));
        String channelGroupId = rootResult.groupState().groupId();

        publish(reconciler.group(List.of(replyMessage())));
        return channelGroupId;
    }

    @Test
    void rootEditWithChangedTextUpdatesThreadGroupImmediatelyAndReportsTheChannelGroupForScheduling() {
        String channelGroupId = seedRootAndReply();

        ChatGroupStateReconciler.RealtimeReconcileResult editResult =
                reconciler.reconcileNonThreadedMessage(rootMessage("root text edited"));

        assertThat(editResult.threadRouted()).isTrue();
        assertThat(editResult.groupState().groupId()).isEqualTo(ROOT_ID);
        assertThat(editResult.groupState().revision()).isEqualTo(1);
        assertThat(rootText(editResult.groupState())).isEqualTo("root text edited");

        assertThat(editResult.channelGroupState()).isNotNull();
        assertThat(editResult.channelGroupState().groupId()).isEqualTo(channelGroupId);
        assertThat(rootText(editResult.channelGroupState())).isEqualTo("root text edited");

        // The thread group's own transcript is part of newAttachmentRecords for a threadRouted
        // result (see RealtimeReconcileResult's javadoc) - publishing it must actually land the
        // edited text on Kafka, not just in GroupState.
        publish(editResult.newAttachmentRecords());
        GroupState persistedThread = groupStateStore.load(RawSourceType.SLACK, TENANT_ID, ROOT_ID).orElseThrow();
        assertThat(persistedThread.published()).isTrue();
        assertThat(persistedThread.revision()).isEqualTo(1);
    }

    @Test
    void replyEditIsUnaffectedByTheFixAndNeverTouchesTheChannelGroup() {
        String channelGroupId = seedRootAndReply();
        GroupState channelBefore = groupStateStore.load(RawSourceType.SLACK, TENANT_ID, channelGroupId).orElseThrow();

        ChatMessage replyEdit = new ChatMessage(
                RawSourceType.SLACK, TENANT_ID, CHAT_ID, "malki", Instant.parse("2026-08-18T09:06:00Z"),
                "reply text edited", List.of(), REPLY_ID, ROOT_ID);
        List<SourceRecord> records = reconciler.group(List.of(replyEdit));
        publish(records);

        assertThat(records).isNotEmpty();
        GroupState threadAfter = groupStateStore.load(RawSourceType.SLACK, TENANT_ID, ROOT_ID).orElseThrow();
        assertThat(threadAfter.messages().stream()
                .filter(m -> m.messageId().equals(REPLY_ID))
                .findFirst().orElseThrow().text()).isEqualTo("reply text edited");

        GroupState channelAfter = groupStateStore.load(RawSourceType.SLACK, TENANT_ID, channelGroupId).orElseThrow();
        assertThat(channelAfter).isEqualTo(channelBefore);
    }

    @Test
    void rootMessageChangedWithUnchangedTextCreatesNoNewRevisionAndIsNotReportedForScheduling() {
        String channelGroupId = seedRootAndReply();
        // The thread group (ROOT_ID) is already published=true/revision=0 - seedRootAndReply's
        // publish() of the reply already ran it through the real lane's afterPublish ->
        // confirmPublished. The channel group is simulated into the same already-confirmed
        // steady state an earlier session-close would have left it in.
        groupStateStore.confirmPublished(RawSourceType.SLACK, TENANT_ID, channelGroupId, 0);

        // Slack re-sends message_changed with the SAME text (e.g. only reply_count changed) -
        // the controller still strips threadId to null before this call (see its javadoc).
        ChatGroupStateReconciler.RealtimeReconcileResult result =
                reconciler.reconcileNonThreadedMessage(rootMessage("root text"));

        assertThat(result.threadRouted()).isTrue();
        assertThat(result.groupState().revision()).isEqualTo(0);
        assertThat(result.newAttachmentRecords()).isEmpty();
        // The fix: an unchanged, already-confirmed channel group must come back null, not the
        // merged-but-identical GroupState - SlackEventsController only calls
        // sessionScheduler.scheduleOrReschedule when this is non-null.
        assertThat(result.channelGroupState()).isNull();

        GroupState channelAfter = groupStateStore.load(RawSourceType.SLACK, TENANT_ID, channelGroupId).orElseThrow();
        assertThat(channelAfter.revision()).isEqualTo(0);
        assertThat(channelAfter.published()).isTrue();
    }

    private static String rootText(GroupState state) {
        return state.messages().stream()
                .filter(m -> m.messageId().equals(ROOT_ID))
                .findFirst()
                .map(MessageSnapshot::text)
                .orElseThrow();
    }
}
