package com.rnd.bridge.intake.unstructured.service;

import com.rnd.bridge.intake.unstructured.config.ChatSessionProperties;
import com.rnd.bridge.intake.unstructured.dto.ChatMessage;
import com.rnd.bridge.intake.unstructured.dto.SourceRecord;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Groups a chat's raw messages into sessions: a run of consecutive messages (same chatId)
 * with no gap larger than the configured threshold. Each session becomes one SourceRecord.
 * See plan.md for why sessions, not individual messages, are this lane's unit of work for chat.
 */
@Service
public class ChatSessionGrouper {

    private final ChatSessionProperties properties;

    public ChatSessionGrouper(ChatSessionProperties properties) {
        this.properties = properties;
    }

    public List<SourceRecord> group(List<ChatMessage> messages) {
        Duration gap = Duration.ofMinutes(properties.sessionGapMinutes());

        Map<String, List<ChatMessage>> byChat = messages.stream()
                .collect(Collectors.groupingBy(ChatMessage::chatId, LinkedHashMap::new, Collectors.toList()));

        List<SourceRecord> sessions = new ArrayList<>();
        for (List<ChatMessage> chatMessages : byChat.values()) {
            List<ChatMessage> sorted = chatMessages.stream()
                    .sorted(Comparator.comparing(ChatMessage::timestamp))
                    .toList();

            List<ChatMessage> currentSession = new ArrayList<>();
            for (ChatMessage message : sorted) {
                if (!currentSession.isEmpty()) {
                    ChatMessage previous = currentSession.get(currentSession.size() - 1);
                    if (Duration.between(previous.timestamp(), message.timestamp()).compareTo(gap) > 0) {
                        sessions.add(toSourceRecord(currentSession));
                        currentSession = new ArrayList<>();
                    }
                }
                currentSession.add(message);
            }
            if (!currentSession.isEmpty()) {
                sessions.add(toSourceRecord(currentSession));
            }
        }
        return sessions;
    }

    private SourceRecord toSourceRecord(List<ChatMessage> sessionMessages) {
        ChatMessage first = sessionMessages.get(0);
        List<String> participants = sessionMessages.stream()
                .map(ChatMessage::sender)
                .distinct()
                .toList();
        String transcript = sessionMessages.stream()
                .map(m -> m.sender() + ": " + m.text())
                .collect(Collectors.joining("\n"));
        String naturalId = first.chatId() + "|" + first.timestamp();
        // A session can mix plain-text and attachment messages; this lane's SourceRecord
        // carries one mimeType per session, so the first attachment found wins. Good
        // enough for a skeleton - a session with two different attachment types is an
        // edge case this doesn't try to solve.
        String mimeType = sessionMessages.stream()
                .map(ChatMessage::attachmentMimeType)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);

        return new SourceRecord(
                first.sourceType(),
                first.tenantId(),
                naturalId,
                first.timestamp(),
                String.join(", ", participants),
                participants,
                null,
                transcript,
                transcript,
                mimeType);
    }
}
