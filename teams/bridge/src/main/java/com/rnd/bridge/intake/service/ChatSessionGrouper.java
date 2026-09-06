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
 * Groups a chat's raw messages into sessions, one SourceRecord per session for the
 * transcript plus one more SourceRecord per attachment across every message in that session
 * (a single message can carry several, e.g. a real Slack "files" array) — sharing the
 * transcript's correlationId (see SourceRecord's javadoc for why attachments get their own
 * record instead of being folded into the transcript's).
 * Two grouping strategies run side by side per chatId: messages with a threadId (a Slack
 * thread reply, or the thread's root) are grouped by threadId directly, regardless of time
 * gap — thread membership is exact, so there's nothing to infer. correlationId is the
 * threadId itself: stable across runs, so a thread touched again later (a new reply) lands
 * under the same id instead of a fresh random one. Messages with no threadId keep the
 * original heuristic: a run of consecutive messages with no gap larger than the configured
 * threshold, correlationId minted fresh per session.
 * Each SourceRecord carries sourceMessageIds — the stable id(s) (ChatMessage.id) of the raw
 * messages it was built from — so a later edit/delete on one of those source messages can be
 * traced back to the record(s) it landed in (not yet consumed anywhere; see SourceRecord's
 * javadoc and Bridge issue #264).
 * See plan.md for why sessions, not individual messages, are this lane's unit of work for chat.
 */
@Service
public class ChatSessionGrouper {

    private final ChatSessionProperties properties;

    public ChatSessionGrouper(ChatSessionProperties properties) {
        this.properties = properties;
    }

    public List<SourceRecord> group(List<ChatMessage> messages) {
        Map<String, List<ChatMessage>> byChat = messages.stream()
                .collect(Collectors.groupingBy(ChatMessage::chatId, LinkedHashMap::new, Collectors.toList()));

        List<SourceRecord> records = new ArrayList<>();
        for (List<ChatMessage> chatMessages : byChat.values()) {
            records.addAll(groupThreaded(chatMessages));
            records.addAll(groupByTimeGap(chatMessages));
        }
        return records;
    }

    private List<SourceRecord> groupThreaded(List<ChatMessage> chatMessages) {
        Map<String, List<ChatMessage>> byThread = chatMessages.stream()
                .filter(m -> m.threadId() != null)
                .collect(Collectors.groupingBy(ChatMessage::threadId, LinkedHashMap::new, Collectors.toList()));

        List<SourceRecord> records = new ArrayList<>();
        for (Map.Entry<String, List<ChatMessage>> thread : byThread.entrySet()) {
            List<ChatMessage> sorted = thread.getValue().stream()
                    .sorted(Comparator.comparing(ChatMessage::timestamp))
                    .toList();
            records.addAll(toSourceRecords(sorted, thread.getKey()));
        }
        return records;
    }

    private List<SourceRecord> groupByTimeGap(List<ChatMessage> chatMessages) {
        Duration gap = Duration.ofMinutes(properties.sessionGapMinutes());
        List<ChatMessage> sorted = chatMessages.stream()
                .filter(m -> m.threadId() == null)
                .sorted(Comparator.comparing(ChatMessage::timestamp))
                .toList();

        List<SourceRecord> records = new ArrayList<>();
        List<ChatMessage> currentSession = new ArrayList<>();
        for (ChatMessage message : sorted) {
            if (!currentSession.isEmpty()) {
                ChatMessage previous = currentSession.get(currentSession.size() - 1);
                if (Duration.between(previous.timestamp(), message.timestamp()).compareTo(gap) > 0) {
                    records.addAll(toSourceRecords(currentSession, UUID.randomUUID().toString()));
                    currentSession = new ArrayList<>();
                }
            }
            currentSession.add(message);
        }
        if (!currentSession.isEmpty()) {
            records.addAll(toSourceRecords(currentSession, UUID.randomUUID().toString()));
        }
        return records;
    }

    private List<SourceRecord> toSourceRecords(List<ChatMessage> sessionMessages, String correlationId) {
        ChatMessage first = sessionMessages.get(0);
        List<String> participants = sessionMessages.stream()
                .map(ChatMessage::sender)
                .distinct()
                .toList();
        String transcript = sessionMessages.stream()
                .map(m -> m.sender() + ": " + m.text())
                .collect(Collectors.joining("\n"));
        String naturalId = first.chatId() + "|" + first.timestamp();
        List<String> sessionMessageIds = sessionMessages.stream()
                .map(ChatMessage::id)
                .filter(id -> id != null)
                .toList();

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
                0,
                sessionMessageIds));

        int attachmentIndex = 0;
        for (ChatMessage message : sessionMessages) {
            for (ChatMessage.Attachment attachment : message.attachments()) {
                attachmentIndex++;
                List<String> ownerMessageId = message.id() == null ? List.of() : List.of(message.id());
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
                        attachmentIndex,
                        ownerMessageId));
            }
        }
        return records;
    }
}
