package com.rnd.bridge.intake.service;

import com.rnd.bridge.intake.config.ChatSessionProperties;
import com.rnd.bridge.intake.model.ChatMessage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Owns grouping policy only for a chat's raw messages into sessions - deciding which
 * ChatMessages belong together, never building SourceRecords or assigning a downstream
 * correlationId (see ChatSourceRecordMapper for that). Two grouping strategies run side by
 * side per chatId: messages with a threadId (a Slack thread reply, or the thread's root) are
 * grouped by threadId directly, regardless of time gap — thread membership is exact, so
 * there's nothing to infer. Messages with no threadId are grouped by the time-gap heuristic: a
 * run of consecutive messages with no gap larger than the configured threshold.
 * groupIntoSessions is the entry point for the stateless path (WhatsApp/fixtures - see
 * SourceRecordLoader), returning each session as a plain message list for the caller to hand
 * to ChatSourceRecordMapper. groupByThread/partitionByTimeGap are the same two strategies
 * exposed individually for ChatGroupStateReconciler's stateful path (Slack), which needs to
 * persist/merge each group under its own identity (threadId, or a minted session UUID) before
 * a SourceRecord is built - see its javadoc.
 * See plan.md for why sessions, not individual messages, are this lane's unit of work for chat.
 */
@Service
public class ChatSessionGrouper {

    private final ChatSessionProperties properties;

    public ChatSessionGrouper(ChatSessionProperties properties) {
        this.properties = properties;
    }

    /** Groups every message into its session, one entry per session, chat by chat: threaded
     * sessions first, then time-gap sessions over whatever's left. No SourceRecord/correlationId
     * involved - see ChatSourceRecordMapper for turning each returned session into records. */
    public List<List<ChatMessage>> groupIntoSessions(List<ChatMessage> messages) {
        Map<String, List<ChatMessage>> byChat = messages.stream()
                .collect(Collectors.groupingBy(ChatMessage::chatId, LinkedHashMap::new, Collectors.toList()));

        List<List<ChatMessage>> sessions = new ArrayList<>();
        for (List<ChatMessage> chatMessages : byChat.values()) {
            sessions.addAll(groupByThread(chatMessages).values());
            List<ChatMessage> withoutThreads = chatMessages.stream()
                    .filter(m -> m.threadId() == null)
                    .sorted(Comparator.comparing(ChatMessage::timestamp))
                    .toList();
            sessions.addAll(partitionByTimeGap(withoutThreads));
        }
        return sessions;
    }

    /**
     * Groups messages by threadId (messages with no threadId are skipped), each thread's
     * messages sorted by timestamp. Shared by groupIntoSessions' own threaded path and
     * ChatGroupStateReconciler's, which persists each thread group under its threadId as
     * groupId instead of turning it straight into a SourceRecord.
     */
    Map<String, List<ChatMessage>> groupByThread(List<ChatMessage> chatMessages) {
        Map<String, List<ChatMessage>> byThread = chatMessages.stream()
                .filter(m -> m.threadId() != null)
                .collect(Collectors.groupingBy(ChatMessage::threadId, LinkedHashMap::new, Collectors.toList()));
        Map<String, List<ChatMessage>> sortedByThread = new LinkedHashMap<>();
        for (Map.Entry<String, List<ChatMessage>> thread : byThread.entrySet()) {
            sortedByThread.put(thread.getKey(), thread.getValue().stream()
                    .sorted(Comparator.comparing(ChatMessage::timestamp))
                    .toList());
        }
        return sortedByThread;
    }

    /**
     * Splits an already timestamp-sorted list of messages into sessions: a run of consecutive
     * messages with no gap larger than the configured threshold. Shared by groupIntoSessions'
     * own time-gap path and ChatGroupStateReconciler's, which persists each returned session
     * under a freshly minted groupId instead of turning it straight into a SourceRecord.
     */
    List<List<ChatMessage>> partitionByTimeGap(List<ChatMessage> sortedMessages) {
        Duration gap = Duration.ofMinutes(properties.sessionGapMinutes());
        List<List<ChatMessage>> sessions = new ArrayList<>();
        List<ChatMessage> currentSession = new ArrayList<>();
        for (ChatMessage message : sortedMessages) {
            if (!currentSession.isEmpty()) {
                ChatMessage previous = currentSession.get(currentSession.size() - 1);
                if (Duration.between(previous.timestamp(), message.timestamp()).compareTo(gap) > 0) {
                    sessions.add(currentSession);
                    currentSession = new ArrayList<>();
                }
            }
            currentSession.add(message);
        }
        if (!currentSession.isEmpty()) {
            sessions.add(currentSession);
        }
        return sessions;
    }
}
