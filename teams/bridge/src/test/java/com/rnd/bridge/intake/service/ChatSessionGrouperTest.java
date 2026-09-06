package com.rnd.bridge.intake.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.rnd.bridge.intake.config.ChatSessionProperties;
import com.rnd.bridge.intake.model.ChatMessage;
import com.rnd.bridge.intake.model.SourceRecord;
import com.rnd.bridge.raw.model.RawSourceType;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatSessionGrouperTest {

    private final ChatSessionGrouper grouper = new ChatSessionGrouper(new ChatSessionProperties(45));

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
    void messagesExactlyAtTheGapThresholdStayInTheSameSession() {
        List<ChatMessage> messages = List.of(
                message("chat-a", "malki", "2026-08-18T09:00:00Z", "hi"),
                message("chat-a", "ruth", "2026-08-18T09:45:00Z", "still here")); // exactly 45 min later

        List<SourceRecord> records = grouper.group(messages);

        assertThat(records).hasSize(1);
        assertThat(records.get(0).body()).contains("malki: hi").contains("ruth: still here");
    }

    @Test
    void messagesOneMinutePastTheThresholdSplitIntoSeparateSessions() {
        List<ChatMessage> messages = List.of(
                message("chat-a", "malki", "2026-08-18T09:00:00Z", "hi"),
                message("chat-a", "ruth", "2026-08-18T09:46:00Z", "you there?")); // 46 min later

        List<SourceRecord> records = grouper.group(messages);

        assertThat(records).hasSize(2);
    }

    @Test
    void differentChatIdsNeverMergeIntoTheSameSessionEvenIfInterleavedInTime() {
        List<ChatMessage> messages = List.of(
                message("work", "ruth", "2026-08-18T09:00:00Z", "deal update?"),
                message("family", "mom", "2026-08-18T09:01:00Z", "dinner friday?"),
                message("work", "malki", "2026-08-18T09:02:00Z", "on it"));

        List<SourceRecord> records = grouper.group(messages);

        assertThat(records).hasSize(2);
        SourceRecord workSession = records.stream()
                .filter(s -> s.body().contains("deal update?"))
                .findFirst().orElseThrow();
        assertThat(workSession.body()).doesNotContain("dinner friday?");
    }

    @Test
    void sessionWithAnAttachmentProducesTwoRecordsSharingACorrelationId() {
        List<ChatMessage> messages = List.of(
                message("chat-a", "malki", "2026-08-18T09:00:00Z", "listen to this"),
                attachment("chat-a", "malki", "2026-08-18T09:01:00Z", "voice-note.opus", "audio/opus"));

        List<SourceRecord> records = grouper.group(messages);

        assertThat(records).hasSize(2);
        SourceRecord body = records.stream().filter(r -> r.attachmentFilename() == null).findFirst().orElseThrow();
        SourceRecord attachmentRecord =
                records.stream().filter(r -> r.attachmentFilename() != null).findFirst().orElseThrow();

        assertThat(body.correlationId()).isEqualTo(attachmentRecord.correlationId());
        assertThat(body.body()).contains("listen to this");
        assertThat(attachmentRecord.attachmentFilename()).isEqualTo("voice-note.opus");
        assertThat(attachmentRecord.mimeType()).isEqualTo("audio/opus");
        assertThat(attachmentRecord.rawContent()).isNotEmpty();
    }

    @Test
    void sessionWithNoAttachmentProducesOnlyTheBodyRecord() {
        List<ChatMessage> messages = List.of(message("chat-a", "malki", "2026-08-18T09:00:00Z", "hi"));

        List<SourceRecord> records = grouper.group(messages);

        assertThat(records).hasSize(1);
        assertThat(records.get(0).attachmentFilename()).isNull();
        assertThat(records.get(0).mimeType()).isNull();
    }

    @Test
    void messagesWithNoStableIdProduceAnEmptySourceMessageIdsList() {
        // WhatsApp/fixture messages carry no platform id - nothing to preserve yet.
        List<ChatMessage> messages = List.of(
                message("chat-a", "malki", "2026-08-18T09:00:00Z", "hi"),
                message("chat-a", "ruth", "2026-08-18T09:01:00Z", "hey"));

        List<SourceRecord> records = grouper.group(messages);

        assertThat(records).hasSize(1);
        assertThat(records.get(0).sourceMessageIds()).isEmpty();
    }

    @Test
    void threadSessionRecordCarriesEveryMessagesStableIdInOrder() {
        List<ChatMessage> messages = List.of(
                threadMessage("chat-a", "malki", "2026-08-18T09:00:00Z", "root question", "t1", "t1"),
                threadMessage("chat-a", "ruth", "2026-08-23T09:00:00Z", "late reply", "t2", "t1"));

        List<SourceRecord> records = grouper.group(messages);

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

        List<SourceRecord> records = grouper.group(List.of(rootWithAttachment));

        SourceRecord attachmentRecord =
                records.stream().filter(r -> r.attachmentFilename() != null).findFirst().orElseThrow();
        assertThat(attachmentRecord.sourceMessageIds()).containsExactly("t1");
    }

    @Test
    void threadRepliesGroupByThreadIdRegardlessOfTimeGap() {
        List<ChatMessage> messages = List.of(
                threadMessage("chat-a", "malki", "2026-08-18T09:00:00Z", "root question", "t1", "t1"),
                // 5 days later - far past the 45-minute gap threshold, still same thread.
                threadMessage("chat-a", "ruth", "2026-08-23T09:00:00Z", "late reply", "t2", "t1"));

        List<SourceRecord> records = grouper.group(messages);

        assertThat(records).hasSize(1);
        assertThat(records.get(0).correlationId()).isEqualTo("t1");
        assertThat(records.get(0).body()).contains("root question").contains("late reply");
    }

    @Test
    void differentThreadsProduceSeparateRecordsEvenInTheSameChannel() {
        List<ChatMessage> messages = List.of(
                threadMessage("chat-a", "malki", "2026-08-18T09:00:00Z", "thread one root", "t1", "t1"),
                threadMessage("chat-a", "ruth", "2026-08-18T09:00:05Z", "thread two root", "t2", "t2"));

        List<SourceRecord> records = grouper.group(messages);

        assertThat(records).hasSize(2);
        assertThat(records.stream().map(SourceRecord::correlationId)).containsExactlyInAnyOrder("t1", "t2");
    }

    @Test
    void threadMessagesAreKeptOutOfTheTimeGapSessionsInTheSameChannel() {
        List<ChatMessage> messages = List.of(
                message("chat-a", "malki", "2026-08-18T09:00:00Z", "plain channel message"),
                threadMessage("chat-a", "ruth", "2026-08-18T09:00:05Z", "thread root", "t1", "t1"));

        List<SourceRecord> records = grouper.group(messages);

        // Same chat, seconds apart - would merge under the old time-gap-only logic, but the
        // threaded message must not leak into the plain session (and vice versa).
        assertThat(records).hasSize(2);
        SourceRecord plainSession = records.stream()
                .filter(r -> r.body().contains("plain channel message"))
                .findFirst().orElseThrow();
        assertThat(plainSession.body()).doesNotContain("thread root");
    }

    @Test
    void aSingleMessageWithTwoAttachmentsProducesTwoDistinctAttachmentRecords() {
        List<ChatMessage> messages = List.of(twoAttachments("chat-a", "ruth", "2026-08-18T09:00:00Z"));

        List<SourceRecord> records = grouper.group(messages);

        // 1 body record + 2 attachment records, all sharing one correlationId
        assertThat(records).hasSize(3);
        assertThat(records.stream().map(SourceRecord::correlationId).distinct()).hasSize(1);
        assertThat(records).anyMatch(r -> "a.jpg".equals(r.attachmentFilename()));
        assertThat(records).anyMatch(r -> "b.pdf".equals(r.attachmentFilename()));
        assertThat(records.stream().map(SourceRecord::naturalId).distinct()).hasSize(3);
    }
}
