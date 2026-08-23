package com.rnd.bridge.intake.unstructured.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.rnd.bridge.intake.unstructured.config.ChatSessionProperties;
import com.rnd.bridge.intake.unstructured.dto.ChatMessage;
import com.rnd.bridge.intake.unstructured.dto.SourceRecord;
import com.rnd.bridge.shared.unstructured.dto.SourceType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatSessionGrouperTest {

    private final ChatSessionGrouper grouper = new ChatSessionGrouper(new ChatSessionProperties(45));

    private ChatMessage message(String chatId, String sender, String isoTimestamp, String text) {
        return new ChatMessage(
                SourceType.WHATSAPP, "kamatech", chatId, sender, Instant.parse(isoTimestamp), text, null);
    }

    private ChatMessage attachment(String chatId, String sender, String isoTimestamp, String mimeType) {
        return new ChatMessage(
                SourceType.WHATSAPP, "kamatech", chatId, sender, Instant.parse(isoTimestamp),
                "<attached: file>", mimeType);
    }

    @Test
    void messagesExactlyAtTheGapThresholdStayInTheSameSession() {
        List<ChatMessage> messages = List.of(
                message("chat-a", "malki", "2026-08-18T09:00:00Z", "hi"),
                message("chat-a", "ruth", "2026-08-18T09:45:00Z", "still here")); // exactly 45 min later

        List<SourceRecord> sessions = grouper.group(messages);

        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).body()).contains("malki: hi").contains("ruth: still here");
    }

    @Test
    void messagesOneMinutePastTheThresholdSplitIntoSeparateSessions() {
        List<ChatMessage> messages = List.of(
                message("chat-a", "malki", "2026-08-18T09:00:00Z", "hi"),
                message("chat-a", "ruth", "2026-08-18T09:46:00Z", "you there?")); // 46 min later

        List<SourceRecord> sessions = grouper.group(messages);

        assertThat(sessions).hasSize(2);
    }

    @Test
    void differentChatIdsNeverMergeIntoTheSameSessionEvenIfInterleavedInTime() {
        List<ChatMessage> messages = List.of(
                message("work", "ruth", "2026-08-18T09:00:00Z", "deal update?"),
                message("family", "mom", "2026-08-18T09:01:00Z", "dinner friday?"),
                message("work", "malki", "2026-08-18T09:02:00Z", "on it"));

        List<SourceRecord> sessions = grouper.group(messages);

        assertThat(sessions).hasSize(2);
        SourceRecord workSession = sessions.stream()
                .filter(s -> s.body().contains("deal update?"))
                .findFirst().orElseThrow();
        assertThat(workSession.body()).doesNotContain("dinner friday?");
    }

    @Test
    void sessionPicksUpMimeTypeFromAnAttachmentMessage() {
        List<ChatMessage> messages = List.of(
                message("chat-a", "malki", "2026-08-18T09:00:00Z", "listen to this"),
                attachment("chat-a", "malki", "2026-08-18T09:01:00Z", "audio/opus"));

        List<SourceRecord> sessions = grouper.group(messages);

        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0).mimeType()).isEqualTo("audio/opus");
    }

    @Test
    void sessionWithNoAttachmentHasNullMimeType() {
        List<ChatMessage> messages = List.of(message("chat-a", "malki", "2026-08-18T09:00:00Z", "hi"));

        List<SourceRecord> sessions = grouper.group(messages);

        assertThat(sessions.get(0).mimeType()).isNull();
    }
}
