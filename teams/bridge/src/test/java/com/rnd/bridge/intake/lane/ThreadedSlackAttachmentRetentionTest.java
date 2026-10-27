package com.rnd.bridge.intake.lane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rnd.bridge.core.IntakeResult;
import com.rnd.bridge.core.PayloadPublisher;
import com.rnd.bridge.core.ReadCursor;
import com.rnd.bridge.core.SourceReader;
import com.rnd.bridge.intake.model.ChatMessage;
import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.intake.parser.SourceRecordGrouper;
import com.rnd.bridge.intake.config.ChatSessionProperties;
import com.rnd.bridge.intake.service.ChatGroupStateReconciler;
import com.rnd.bridge.intake.service.ChatSessionGrouper;
import com.rnd.bridge.intake.service.ChatSourceRecordMapper;
import com.rnd.bridge.intake.service.IntakeLandingService;
import com.rnd.bridge.intake.service.SourceRecordRefMapper;
import com.rnd.bridge.raw.RawObjectValidator;
import com.rnd.bridge.raw.TestTenantId;
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
 * End-to-end reproduction of the bug reported against thread 1b806270-...: a threaded reply
 * with a file published revision 0 with 2 objects (text + file); a later text-only reply in the
 * same thread published revision 1 with only 1 object, silently dropping the earlier file's
 * reference even though the file itself was still safely in MinIO. Wires a real
 * ChatGroupStateReconciler + ChatSourceRecordMapper + GroupStateStore + UnstructuredIntakeLane
 * (over an in-memory ObjectStorageClient) - only the Kafka send is mocked - so this exercises the
 * actual re-attach path (ChatSourceRecordMapper.toSourceRecords(GroupState, List) reading
 * MessageSnapshot.landedAttachments()) together with UnstructuredIntakeLane.afterPublish's new
 * recordLandedAttachment call, the same way SlackEventsController.reconcileAndPublish's threaded
 * branch actually runs it.
 */
class ThreadedSlackAttachmentRetentionTest {

    private static final String TENANT_ID = TestTenantId.VALID;
    private static final String CHAT_ID = "chat-a";
    private static final String THREAD_ID = "1000.0";

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

    private ChatMessage replyWithFile() {
        ChatMessage.Attachment attachment =
                new ChatMessage.Attachment("F1", "note.txt", "text/plain", "hello from the file".getBytes());
        return new ChatMessage(
                RawSourceType.SLACK, TENANT_ID, CHAT_ID, "ruth", Instant.parse("2026-08-18T09:00:00Z"),
                "see attached", List.of(attachment), "1000.2", THREAD_ID);
    }

    private ChatMessage textOnlyReply() {
        return new ChatMessage(
                RawSourceType.SLACK, TENANT_ID, CHAT_ID, "ruth", Instant.parse("2026-08-18T09:05:00Z"),
                "just text, no file", List.of(), "1000.3", THREAD_ID);
    }

    private RawObjectEvent runOneReply(ChatMessage message) {
        List<SourceRecord> records = reconciler.group(List.of(message));
        List<List<SourceRecord>> groups = SourceRecordGrouper.groupByCorrelationId(records);
        when(sourceReader.read(any())).thenReturn(groups.stream());

        IntakeResult result = lane.run(ReadCursor.full(), Instant.now());
        assertThat(result).isEqualTo(new IntakeResult(1, 1, 0, List.of()));

        ArgumentCaptor<RawObjectEvent> captor = ArgumentCaptor.forClass(RawObjectEvent.class);
        org.mockito.Mockito.verify(kafkaPublisher, org.mockito.Mockito.atLeastOnce()).publish(captor.capture());
        return captor.getValue();
    }

    @Test
    void revision0PublishesTextPlusFileThenRevision1StillCarriesTheSameFileReference() {
        RawObjectEvent revision0 = runOneReply(replyWithFile());
        assertThat(revision0.objects()).hasSize(2);
        RawObjectRef fileRef = revision0.objects().stream()
                .filter(ref -> ref.role() == Role.ATTACHMENT)
                .findFirst().orElseThrow();
        assertThat(fileRef.objectKey()).contains("note.txt");

        RawObjectEvent revision1 = runOneReply(textOnlyReply());

        assertThat(revision1.objects()).hasSize(2);
        RawObjectRef carriedFileRef = revision1.objects().stream()
                .filter(ref -> ref.role() == Role.ATTACHMENT)
                .findFirst().orElseThrow();
        assertThat(carriedFileRef.objectKey()).isEqualTo(fileRef.objectKey());
        assertThat(carriedFileRef.contentHash()).isEqualTo(fileRef.contentHash());
    }
}
