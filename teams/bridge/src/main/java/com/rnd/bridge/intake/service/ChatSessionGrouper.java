package com.rnd.bridge.intake.service;

import com.rnd.bridge.intake.config.ChatSessionProperties;
import com.rnd.bridge.intake.model.ChatMessage;
import com.rnd.bridge.intake.model.SourceRecord;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Groups a chat's raw messages into sessions: a run of consecutive messages (same chatId)
 * with no gap larger than the configured threshold. Each session becomes one SourceRecord
 * for the transcript, plus one more SourceRecord per attachment across every message in
 * that session (a single message can carry several, e.g. a real Slack "files" array) —
 * sharing the transcript's correlationId (see SourceRecord's javadoc for why attachments
 * get their own record instead of being folded into the transcript's).
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

        List<SourceRecord> records = new ArrayList<>();
        for (List<ChatMessage> chatMessages : byChat.values()) {
            List<ChatMessage> sorted = chatMessages.stream()
                    .sorted(Comparator.comparing(ChatMessage::timestamp))
                    .toList();

            List<ChatMessage> currentSession = new ArrayList<>();
            for (ChatMessage message : sorted) {
                if (!currentSession.isEmpty()) {
                    ChatMessage previous = currentSession.get(currentSession.size() - 1);
                    if (Duration.between(previous.timestamp(), message.timestamp()).compareTo(gap) > 0) {
                        records.addAll(toSourceRecords(currentSession));
                        currentSession = new ArrayList<>();
                    }
                }
                currentSession.add(message);
            }
            if (!currentSession.isEmpty()) {
                records.addAll(toSourceRecords(currentSession));
            }
        }
        return records;
    }

    private List<SourceRecord> toSourceRecords(List<ChatMessage> sessionMessages) {
        ChatMessage first = sessionMessages.get(0);
        List<String> participants = sessionMessages.stream()
                .map(ChatMessage::sender)
                .distinct()
                .toList();
        String transcript = sessionMessages.stream()
                .map(m -> m.sender() + ": " + m.text())
                .collect(Collectors.joining("\n"));
        String naturalId = first.chatId() + "|" + first.timestamp();
        String correlationId = UUID.randomUUID().toString();

        List<SourceRecord> records = new ArrayList<>();
        records.add(new SourceRecord(
                first.sourceType(),
                first.tenantId(),
                naturalId,
                correlationId,
                first.timestamp(),
                String.join(", ", participants),
                participants,
                null,
                transcript,
                transcript.getBytes(StandardCharsets.UTF_8),
                null,
                null,
                0));

        int attachmentIndex = 0;
        for (ChatMessage message : sessionMessages) {
            for (ChatMessage.Attachment attachment : message.attachments()) {
                attachmentIndex++;
                records.add(new SourceRecord(
                        message.sourceType(),
                        message.tenantId(),
                        naturalId + "|attachment-" + attachmentIndex,
                        correlationId,
                        message.timestamp(),
                        message.sender(),
                        List.of(message.sender()),
                        null,
                        null,
                        attachment.content(),
                        attachment.mimeType(),
                        attachment.filename(),
                        attachmentIndex));
            }
        }
        return records;
    }
}
