package com.rnd.bridge.intake.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.rnd.bridge.intake.model.ChatMessage;
import com.rnd.bridge.intake.model.GroupState;
import com.rnd.bridge.intake.model.MessageSnapshot;
import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.raw.model.RawSourceType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Covers ChatSourceRecordMapper - the one class responsible for turning an already-grouped
 * session (or a persisted GroupState) into SourceRecords: transcript, participants, naturalId,
 * sourceMessageIds, attachments, and correlationId/revision propagation. */
class ChatSourceRecordMapperTest {

    private final ChatSourceRecordMapper mapper = new ChatSourceRecordMapper();

    private ChatMessage message(String chatId, String sender, String isoTimestamp, String text) {
        return new ChatMessage(
                RawSourceType.WHATSAPP, "kamatech", chatId, sender, Instant.parse(isoTimestamp), text, List.of());
    }

    private ChatMessage threadMessage(
            String chatId, String sender, String isoTimestamp, String text, String id, String threadId) {
        return new ChatMessage(
                RawSourceType.SLACK, "kamatech", chatId, sender, Instant.parse(isoTimestamp), text, List.of(),
                id, threadId);
    }

    private ChatMessage attachment(String chatId, String sender, String isoTimestamp, String filename, String mimeType) {
        ChatMessage.Attachment attachment =
                new ChatMessage.Attachment(filename, mimeType, "fake-bytes".getBytes(StandardCharsets.UTF_8));
        return new ChatMessage(
                RawSourceType.WHATSAPP, "kamatech", chatId, sender, Instant.parse(isoTimestamp),
                "<attached: " + filename + ">", List.of(attachment));
    }

    private ChatMessage twoAttachments(String chatId, String sender, String isoTimestamp) {
        ChatMessage.Attachment first =
                new ChatMessage.Attachment("a.jpg", "image/jpeg", "fake-a".getBytes(StandardCharsets.UTF_8));
        ChatMessage.Attachment second =
                new ChatMessage.Attachment("b.pdf", "application/pdf", "fake-b".getBytes(StandardCharsets.UTF_8));
        return new ChatMessage(
                RawSourceType.SLACK, "kamatech", chatId, sender, Instant.parse(isoTimestamp),
                "sharing two files", List.of(first, second));
    }

    @Test
    void sessionWithAnAttachmentProducesTwoRecordsSharingACorrelationId() {
        List<ChatMessage> session = List.of(
                message("chat-a", "malki", "2026-08-18T09:00:00Z", "listen to this"),
                attachment("chat-a", "malki", "2026-08-18T09:01:00Z", "voice-note.opus", "audio/opus"));

        List<SourceRecord> records = mapper.toSourceRecords(session);

        assertThat(records).hasSize(2);
        SourceRecord body = records.stream().filter(r -> r.attachmentFilename() == null).findFirst().orElseThrow();
        SourceRecord attachmentRecord =
                records.stream().filter(r -> r.attachmentFilename() != null).findFirst().orElseThrow();

        assertThat(body.correlationId()).isEqualTo(attachmentRecord.correlationId());
        assertThat(body.body()).contains("listen to this");
        assertThat(attachmentRecord.attachmentFilename()).isEqualTo("voice-note.opus");
        assertThat(attachmentRecord.mimeType()).isEqualTo("audio/opus");
        assertThat(attachmentRecord.rawContent()).isNotEmpty();
        // Body and its one attachment must report the same total.
        assertThat(body.attachmentCount()).isEqualTo(1);
        assertThat(attachmentRecord.attachmentCount()).isEqualTo(1);
        assertThat(body.attachmentIndex()).isZero();
        assertThat(attachmentRecord.attachmentIndex()).isEqualTo(1);
    }

    @Test
    void sessionWithNoAttachmentProducesOnlyTheBodyRecord() {
        List<ChatMessage> session = List.of(message("chat-a", "malki", "2026-08-18T09:00:00Z", "hi"));

        List<SourceRecord> records = mapper.toSourceRecords(session);

        assertThat(records).hasSize(1);
        assertThat(records.get(0).attachmentFilename()).isNull();
        assertThat(records.get(0).mimeType()).isNull();
        assertThat(records.get(0).attachmentCount()).isZero();
    }

    @Test
    void messagesWithNoStableIdProduceAnEmptySourceMessageIdsList() {
        List<ChatMessage> session = List.of(
                message("chat-a", "malki", "2026-08-18T09:00:00Z", "hi"),
                message("chat-a", "ruth", "2026-08-18T09:01:00Z", "hey"));

        List<SourceRecord> records = mapper.toSourceRecords(session);

        assertThat(records).hasSize(1);
        assertThat(records.get(0).sourceMessageIds()).isEmpty();
    }

    @Test
    void sourceMessageIdsReflectsTheGivenSessionOrder() {
        List<ChatMessage> session = List.of(
                threadMessage("chat-a", "malki", "2026-08-18T09:00:00Z", "root question", "t1", "t1"),
                threadMessage("chat-a", "ruth", "2026-08-23T09:00:00Z", "late reply", "t2", "t1"));

        List<SourceRecord> records = mapper.toSourceRecords(session);

        assertThat(records).hasSize(1);
        assertThat(records.get(0).sourceMessageIds()).containsExactly("t1", "t2");
    }

    @Test
    void attachmentRecordCarriesOnlyItsOwningMessagesStableId() {
        List<ChatMessage.Attachment> attachments =
                List.of(new ChatMessage.Attachment("voice-note.opus", "audio/opus",
                        "fake-bytes".getBytes(StandardCharsets.UTF_8)));
        ChatMessage rootWithAttachment = new ChatMessage(
                RawSourceType.SLACK, "kamatech", "chat-a", "malki", Instant.parse("2026-08-18T09:00:00Z"),
                "listen to this", attachments, "t1", "t1");

        List<SourceRecord> records = mapper.toSourceRecords(List.of(rootWithAttachment));

        SourceRecord attachmentRecord =
                records.stream().filter(r -> r.attachmentFilename() != null).findFirst().orElseThrow();
        assertThat(attachmentRecord.sourceMessageIds()).containsExactly("t1");
    }

    @Test
    void aSingleMessageWithTwoAttachmentsProducesTwoDistinctAttachmentRecords() {
        List<ChatMessage> session = List.of(twoAttachments("chat-a", "ruth", "2026-08-18T09:00:00Z"));

        List<SourceRecord> records = mapper.toSourceRecords(session);

        assertThat(records).hasSize(3);
        assertThat(records.stream().map(SourceRecord::correlationId).distinct()).hasSize(1);
        assertThat(records).anyMatch(r -> "a.jpg".equals(r.attachmentFilename()));
        assertThat(records).anyMatch(r -> "b.pdf".equals(r.attachmentFilename()));
        assertThat(records.stream().map(SourceRecord::naturalId).distinct()).hasSize(3);
        // Body plus both attachments must all agree on the same total of 2.
        assertThat(records).extracting(SourceRecord::attachmentCount).containsOnly(2);
        assertThat(records.stream().map(SourceRecord::attachmentIndex).sorted().toList()).containsExactly(0, 1, 2);
    }

    @Test
    void statelessSessionGetsAValidUuidCorrelationId() {
        List<ChatMessage> session = List.of(message("chat-a", "ruth", "2026-08-18T09:00:00Z", "hi"));

        List<SourceRecord> records = mapper.toSourceRecords(session);

        String correlationId = records.get(0).correlationId();
        assertThat(UUID.fromString(correlationId)).isNotNull();
    }

    @Test
    void threadedStatelessSessionNeverLeaksTheThreadIdAsCorrelationId() {
        List<ChatMessage> session = List.of(
                threadMessage("chat-a", "malki", "2026-08-18T09:00:00Z", "root question", "t1", "t1"));

        List<SourceRecord> records = mapper.toSourceRecords(session);

        String correlationId = records.get(0).correlationId();
        assertThat(correlationId).isNotEqualTo("t1");
        assertThat(UUID.fromString(correlationId)).isNotNull();
    }

    @Test
    void persistedGroupUsesGroupStateCorrelationIdNotGroupId() {
        GroupState mergedGroup = new GroupState(
                "t1", RawSourceType.SLACK, "kamatech", "chat-a", "t1", "fixed-correlation-uuid",
                List.of(new MessageSnapshot("m1", "ruth", Instant.parse("2026-08-18T09:00:00Z"), "hi", 0)),
                false, 0, null);

        List<SourceRecord> records = mapper.toSourceRecords(mergedGroup, List.of());

        assertThat(records).hasSize(1);
        assertThat(records.get(0).correlationId()).isEqualTo("fixed-correlation-uuid");
        assertThat(records.get(0).correlationId()).isNotEqualTo(mergedGroup.groupId());
    }

    @Test
    void persistedGroupTranscriptAndAttachmentShareTheSameCorrelationId() {
        // mergedGroup reflects the actual post-merge state the real reconciler would produce:
        // ChatGroupStateReconciler.mergeSnapshots always merges m1's incoming attachment into
        // its MessageSnapshot before ChatSourceRecordMapper ever sees this GroupState, so m1's
        // persisted attachmentCount here already matches the incoming message (1, not 0).
        ChatMessage.Attachment attachment =
                new ChatMessage.Attachment("note.txt", "text/plain", "fake-bytes".getBytes(StandardCharsets.UTF_8));
        ChatMessage newMessageWithAttachment = new ChatMessage(
                RawSourceType.SLACK, "kamatech", "chat-a", "ruth", Instant.parse("2026-08-18T09:00:00Z"),
                "listen to this", List.of(attachment), "m1", null);
        GroupState mergedGroup = new GroupState(
                "g1", RawSourceType.SLACK, "kamatech", "chat-a", null, "fixed-correlation-uuid",
                List.of(new MessageSnapshot("m1", "ruth", Instant.parse("2026-08-18T09:00:00Z"), "listen to this", 1)),
                false, 0, null);

        List<SourceRecord> records = mapper.toSourceRecords(mergedGroup, List.of(newMessageWithAttachment));

        assertThat(records).hasSize(2);
        assertThat(records.get(0).correlationId()).isEqualTo("fixed-correlation-uuid");
        assertThat(records.get(1).correlationId()).isEqualTo("fixed-correlation-uuid");
        // Both parts of this batch (transcript + the newly emitted attachment) must agree.
        assertThat(records.get(0).attachmentCount()).isEqualTo(1);
        assertThat(records.get(1).attachmentCount()).isEqualTo(1);
    }

    @Test
    void attachmentRecordCarriesTheAttachmentsStableFileIdButTheTranscriptDoesNot() {
        ChatMessage.Attachment attachment =
                new ChatMessage.Attachment("F123", "note.txt", "text/plain", "fake-bytes".getBytes(StandardCharsets.UTF_8));
        ChatMessage messageWithAttachment = new ChatMessage(
                RawSourceType.SLACK, "kamatech", "chat-a", "ruth", Instant.parse("2026-08-18T09:00:00Z"),
                "listen to this", List.of(attachment), "m1", null);

        List<SourceRecord> stateless = mapper.toSourceRecords(List.of(messageWithAttachment));
        assertThat(stateless.get(0).attachmentFileId()).isNull(); // transcript
        assertThat(stateless.get(1).attachmentFileId()).isEqualTo("F123"); // attachment

        GroupState mergedGroup = new GroupState(
                "g1", RawSourceType.SLACK, "kamatech", "chat-a", null, "fixed-correlation-uuid",
                List.of(new MessageSnapshot("m1", "ruth", Instant.parse("2026-08-18T09:00:00Z"), "listen to this", 1)),
                false, 0, null);
        List<SourceRecord> persisted = mapper.toSourceRecords(mergedGroup, List.of(messageWithAttachment));
        assertThat(persisted.get(0).attachmentFileId()).isNull(); // transcript
        assertThat(persisted.get(1).attachmentFileId()).isEqualTo("F123"); // attachment
    }

    /**
     * The scenario MessageSnapshot.attachmentCount exists for: an attachment landed in an
     * earlier intake run (m1, persisted with attachmentCount=1) is never re-emitted as a
     * SourceRecord in this batch - only a genuinely new, attachment-less message (m2) is. The
     * body record built for this batch must still report the group's true total (1), not just
     * what's visible in newMessagesThisBatch (which would wrongly compute 0).
     */
    @Test
    void persistedGroupTotalsAttachmentCountAcrossMessagesFromEarlierRunsNotJustThisBatch() {
        ChatMessage newMessageNoAttachment = new ChatMessage(
                RawSourceType.SLACK, "kamatech", "chat-a", "malki", Instant.parse("2026-08-18T09:10:00Z"),
                "thanks", List.of(), "m2", null);
        GroupState mergedGroup = new GroupState(
                "g1", RawSourceType.SLACK, "kamatech", "chat-a", null, "fixed-correlation-uuid",
                List.of(
                        new MessageSnapshot("m1", "ruth", Instant.parse("2026-08-18T09:00:00Z"), "listen to this", 1),
                        new MessageSnapshot("m2", "malki", Instant.parse("2026-08-18T09:10:00Z"), "thanks", 0)),
                false, 1, null);

        List<SourceRecord> records = mapper.toSourceRecords(mergedGroup, List.of(newMessageNoAttachment));

        assertThat(records).hasSize(1); // only the body - m2 has no attachment to emit
        assertThat(records.get(0).attachmentCount()).isEqualTo(1);
    }

    @Test
    void persistedGroupCarriesItsRevisionOntoEveryRecord() {
        GroupState mergedGroup = new GroupState(
                "t1", RawSourceType.SLACK, "kamatech", "chat-a", "t1", "fixed-correlation-uuid",
                List.of(new MessageSnapshot("m1", "ruth", Instant.parse("2026-08-18T09:00:00Z"), "hi", 0)),
                true, 3, null);

        List<SourceRecord> records = mapper.toSourceRecords(mergedGroup, List.of());

        assertThat(records.get(0).revision()).isEqualTo(3);
    }

    @Test
    void statelessSlackSessionGetsAChannelIdColonTsSourceRef() {
        List<ChatMessage> session = List.of(
                threadMessage("chat-a", "malki", "2026-08-18T09:00:00Z", "root question", "t1", "t1"));

        List<SourceRecord> records = mapper.toSourceRecords(session);

        assertThat(records.get(0).sourceRef()).isEqualTo("chat-a:t1");
    }

    @Test
    void statelessWhatsAppSessionGetsNoSourceRef() {
        List<ChatMessage> session = List.of(message("chat-a", "malki", "2026-08-18T09:00:00Z", "hi"));

        List<SourceRecord> records = mapper.toSourceRecords(session);

        assertThat(records.get(0).sourceRef()).isNull();
    }

    @Test
    void persistedSlackGroupGetsAChannelIdColonTsSourceRef() {
        GroupState mergedGroup = new GroupState(
                "t1", RawSourceType.SLACK, "kamatech", "chat-a", "t1", "fixed-correlation-uuid",
                List.of(new MessageSnapshot("m1", "ruth", Instant.parse("2026-08-18T09:00:00Z"), "hi", 0)),
                false, 0, null);

        List<SourceRecord> records = mapper.toSourceRecords(mergedGroup, List.of());

        assertThat(records.get(0).sourceRef()).isEqualTo("chat-a:m1");
    }

    @Test
    void anEmptiedGroupProducesExactlyOneDeleteReviewRecord() {
        GroupState emptiedGroup = new GroupState(
                "g1", RawSourceType.SLACK, "kamatech", "chat-a", null, "fixed-correlation-uuid", List.of(), false, 1,
                null);

        List<SourceRecord> records = mapper.toSourceRecords(emptiedGroup, List.of());

        assertThat(records).hasSize(1);
        SourceRecord record = records.get(0);
        assertThat(record.changeType()).isEqualTo(com.rnd.bridge.raw.model.ChangeType.DELETE_REVIEW);
        assertThat(record.correlationId()).isEqualTo("fixed-correlation-uuid");
        assertThat(record.revision()).isEqualTo(1);
        assertThat(record.groupId()).isEqualTo("g1");
        assertThat(record.attachmentIndex()).isZero();
        assertThat(record.attachmentCount()).isZero();
    }
}
