package com.rnd.bridge.intake.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.rnd.bridge.intake.config.ChatSessionProperties;
import com.rnd.bridge.intake.model.ChatMessage;
import com.rnd.bridge.raw.model.RawSourceType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Covers ChatSessionGrouper's one responsibility - grouping policy - never SourceRecord
 * construction; see ChatSourceRecordMapperTest for that. */
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

    @Test
    void messagesExactlyAtTheGapThresholdStayInTheSameSession() {
        List<ChatMessage> messages = List.of(
                message("chat-a", "malki", "2026-08-18T09:00:00Z", "hi"),
                message("chat-a", "ruth", "2026-08-18T09:45:00Z", "still here")); // exactly 45 min later

        List<List<ChatMessage>> sessions = grouper.groupIntoSessions(messages);

        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0)).hasSize(2);
    }

    @Test
    void messagesOneMinutePastTheThresholdSplitIntoSeparateSessions() {
        List<ChatMessage> messages = List.of(
                message("chat-a", "malki", "2026-08-18T09:00:00Z", "hi"),
                message("chat-a", "ruth", "2026-08-18T09:46:00Z", "you there?")); // 46 min later

        List<List<ChatMessage>> sessions = grouper.groupIntoSessions(messages);

        assertThat(sessions).hasSize(2);
    }

    @Test
    void differentChatIdsNeverMergeIntoTheSameSessionEvenIfInterleavedInTime() {
        List<ChatMessage> messages = List.of(
                message("work", "ruth", "2026-08-18T09:00:00Z", "deal update?"),
                message("family", "mom", "2026-08-18T09:01:00Z", "dinner friday?"),
                message("work", "malki", "2026-08-18T09:02:00Z", "on it"));

        List<List<ChatMessage>> sessions = grouper.groupIntoSessions(messages);

        assertThat(sessions).hasSize(2);
        List<ChatMessage> workSession = sessions.stream()
                .filter(s -> s.stream().anyMatch(m -> "deal update?".equals(m.text())))
                .findFirst().orElseThrow();
        assertThat(workSession).hasSize(2); // "deal update?" + "on it"
        assertThat(workSession).noneMatch(m -> "dinner friday?".equals(m.text()));
    }

    @Test
    void threadRepliesGroupByThreadIdRegardlessOfTimeGap() {
        List<ChatMessage> messages = List.of(
                threadMessage("chat-a", "malki", "2026-08-18T09:00:00Z", "root question", "t1", "t1"),
                // 5 days later - far past the 45-minute gap threshold, still same thread.
                threadMessage("chat-a", "ruth", "2026-08-23T09:00:00Z", "late reply", "t2", "t1"));

        List<List<ChatMessage>> sessions = grouper.groupIntoSessions(messages);

        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0)).extracting(ChatMessage::id).containsExactly("t1", "t2");
    }

    @Test
    void differentThreadsProduceSeparateSessionsEvenInTheSameChannel() {
        List<ChatMessage> messages = List.of(
                threadMessage("chat-a", "malki", "2026-08-18T09:00:00Z", "thread one root", "t1", "t1"),
                threadMessage("chat-a", "ruth", "2026-08-18T09:00:05Z", "thread two root", "t2", "t2"));

        List<List<ChatMessage>> sessions = grouper.groupIntoSessions(messages);

        assertThat(sessions).hasSize(2);
        assertThat(sessions.stream().flatMap(List::stream).map(ChatMessage::threadId))
                .containsExactlyInAnyOrder("t1", "t2");
    }

    @Test
    void threadMessagesAreKeptOutOfTheTimeGapSessionsInTheSameChannel() {
        List<ChatMessage> messages = List.of(
                message("chat-a", "malki", "2026-08-18T09:00:00Z", "plain channel message"),
                threadMessage("chat-a", "ruth", "2026-08-18T09:00:05Z", "thread root", "t1", "t1"));

        List<List<ChatMessage>> sessions = grouper.groupIntoSessions(messages);

        // Same chat, seconds apart - would merge under a naive time-gap-only heuristic, but the
        // threaded message must not leak into the plain session (and vice versa).
        assertThat(sessions).hasSize(2);
        List<ChatMessage> plainSession = sessions.stream()
                .filter(s -> s.stream().anyMatch(m -> "plain channel message".equals(m.text())))
                .findFirst().orElseThrow();
        assertThat(plainSession).noneMatch(m -> "thread root".equals(m.text()));
    }

    @Test
    void threadSessionMessagesAreOrderedByTimestampRegardlessOfArrivalOrder() {
        List<ChatMessage> messages = List.of(
                threadMessage("chat-a", "ruth", "2026-08-18T09:10:00Z", "second", "t2", "t1"),
                threadMessage("chat-a", "malki", "2026-08-18T09:00:00Z", "first", "t1", "t1"));

        List<List<ChatMessage>> sessions = grouper.groupIntoSessions(messages);

        assertThat(sessions).hasSize(1);
        assertThat(sessions.get(0)).extracting(ChatMessage::text).containsExactly("first", "second");
    }
}
